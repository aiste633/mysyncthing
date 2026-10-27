#!/usr/bin/env python3
"""
Fails if Kotlin code holds text for people instead of a string resource.

Every string literal in the app's and core's main sources is checked: one that
reads like words (two words in a row, a word next to a template, or one
capitalised word) must be on the allowlist below, or be a message for
developers: logs, exceptions, preconditions and toString() stay English. Text for people goes in res/values/strings.xml, so it can be
translated; see docs/translations.md.

Raw strings (triple quotes) are skipped: they hold code such as HTML and
JavaScript. Template expressions (${...}) count as a placeholder, not as words.

It also checks every use of a string resource (stringResource, text, plural,
getString and their plural forms): it must pass as many arguments as the
string has placeholders, since a missing one crashes at run time, and no
formatted text where the string expects a number (%d).

Usage: scripts/check-strings.py      (from anywhere in the repository)
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOTS = ["app/src/main/java", "core/src/main/java"]

# --- Allowlist: (file, literal) as regular expressions, each with why it stays in code.
ALLOWED = [
    # The core's own messages, matched to show our words instead (ProblemText, LiveStats).
    (r"core/ProblemText\.kt", r"insufficient space .*|no space left on device"),
    (r"ui/LiveStats\.kt", r"network is unreachable"),
    # A directory name on disk, made before the user picks one; not shown as text.
    (r"ui/Validation\.kt", r"Folder"),
    # Names of libraries and licences.
    (r"ui/AboutScreen\.kt", r"Apache-2\.0|Apache License 2\.0|Kotlin, kotlinx\..*|OkHttp, Okio"),
    # Date patterns, made locale-aware separately.
    (r"ui/Format\.kt", r"d MMM( yyyy)?(, HH:mm)?"),
    # HTTP protocol words.
    (r"ui/WebGuiScreen\.kt", r"Basic |Authorization"),
]

# Messages for developers: logs, exceptions and preconditions stay English.
DEVELOPER = re.compile(r"\b(Log\.[vdiwe]|error|check|checkNotNull|require|requireNotNull|println|\w*Exception|\w*Error)\s*\($")

TO_STRING = re.compile(r"\bfun toString\(\)\s*(:\s*String\s*)?=\s*$")

WORDS = re.compile(r"[A-Za-z]{2,}[,.:;!?]? +[A-Za-z]{2,}")
CAPITALISED = re.compile(r"^[A-Z][a-z]+\s*$")
# A word next to a template, as in "$n ago" or "by $name".
TEMPLATE_WORD = re.compile(r"\{\}[^A-Za-z{}]* [A-Za-z]{2,}|[A-Za-z]{2,} [^A-Za-z{}]*\{\}")


def literals(src):
    """Yields (line, text, call) for each "..." literal: its text with templates as {}, and the code just before it."""
    i, n, line = 0, len(src), 1

    def skip_string(i):
        """i is just after the opening quote; returns (end index after the closing quote, text)."""
        nonlocal line
        out = []
        while i < n:
            c = src[i]
            if c == "\\":
                out.append(src[i:i + 2]); i += 2; continue
            if c == '"':
                return i + 1, "".join(out)
            if c == "\n":
                line += 1
            if c == "$" and i + 1 < n and src[i + 1] == "{":
                i = skip_code(i + 2, "}"); out.append("{}"); continue
            if c == "$" and i + 1 < n and (src[i + 1].isalpha() or src[i + 1] == "_"):
                i += 1
                while i < n and (src[i].isalnum() or src[i] == "_"):
                    i += 1
                out.append("{}"); continue
            out.append(c); i += 1
        return i, "".join(out)

    def skip_code(i, close):
        """Skips code up to the matching close brace, strings and comments included."""
        nonlocal line
        depth = 1
        while i < n:
            if src.startswith('"""', i):
                j = src.index('"""', i + 3); line += src.count("\n", i, j); i = j + 3; continue
            c = src[i]
            if c == '"':
                i, _ = skip_string(i + 1); continue
            if c == "\n":
                line += 1
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
        return i

    while i < n:
        if src.startswith("//", i):
            i = src.find("\n", i)
            i = n if i < 0 else i
            continue
        if src.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth += 1; i += 2
                elif src.startswith("*/", i):
                    depth -= 1; i += 2
                else:
                    line += src[i] == "\n"; i += 1
            continue
        if src.startswith('"""', i):
            j = src.index('"""', i + 3)
            while src.startswith('"', j + 3):  # """ followed by more quotes ends at the last three
                j += 1
            line += src.count("\n", i, j); i = j + 3
            continue
        c = src[i]
        if c == "'":  # a Char literal, possibly '"'
            j = src.index("'", i + 2 if src[i + 1] == "\\" else i + 1)
            i = j + 1
            continue
        if c == '"':
            start, before = line, src[max(0, i - 200):i]
            i, text = skip_string(i + 1)
            yield start, text, before
            continue
        line += c == "\n"
        i += 1


def call_before(before):
    """The innermost call still open where the literal starts, such as `Log.w(TAG, `."""
    depth = 0
    for k in range(len(before) - 1, -1, -1):
        ch = before[k]
        if ch == ")":
            depth += 1
        elif ch == "(":
            if depth == 0:
                return before[:k + 1].rstrip()
            depth -= 1
    return ""


PLACEHOLDER = re.compile(r"%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")
USE = re.compile(r"\b(stringResource|text|plural|pluralStringResource|getString|getQuantityString)\(\s*(?:\w+\.)?R\.(string|plurals)\.(\w+)")
# Arguments that are text, not a number.
NOT_A_NUMBER = re.compile(r'^"|asString\(|stringResource\(|\bbytes\(|\bgrouped\(')


def placeholders(top):
    """(kind, name) -> {argument number: conversion} for every string and plural in the modules."""
    out = {}
    for root in ROOTS:
        for f in (top / root).parent.joinpath("res/values").glob("*.xml"):
            for e in ET.parse(f).getroot():
                if e.tag not in ("string", "plurals"):
                    continue
                texts = [e.text or ""] if e.tag == "string" else [i.text or "" for i in e]
                out[(e.tag, e.get("name"))] = {int(n): c for t in texts for n, c in PLACEHOLDER.findall(t)}
    return out


def closing(src, i):
    """Index of the bracket closing the one at i, skipping string literals."""
    depth = 0
    while True:
        c = src[i]
        if c == '"':
            i += 1
            while src[i] != '"':
                i += 2 if src[i] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i
        i += 1


def arguments(src):
    """The top-level, comma-separated arguments in src."""
    out, depth, cur, i = [], 0, "", 0
    while i < len(src):
        c = src[i]
        if c == '"':
            j = i + 1
            while src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            cur += src[i:j + 1]; i = j + 1
            continue
        depth += c in "([{"
        depth -= c in ")]}"
        if c == "," and depth == 0:
            out.append(cur.strip()); cur = ""
        else:
            cur += c
        i += 1
    return out + [cur.strip()] if cur.strip() else out


def check_uses(src, formats):
    """Problems with the arguments passed to string resources in one file."""
    for m in USE.finditer(src):
        fn, kind, name = m.groups()
        line = src.count("\n", 0, m.start()) + 1
        need = formats.get(("plurals" if kind == "plurals" else "string", name))
        if need is None:
            yield line, f"no {kind} resource {name}"
            continue
        start = src.index("(", m.start())
        args = arguments(src[start + 1:closing(src, start)])[1:]
        if kind == "plurals":
            count, args = args[0], args[1:]
            if fn == "plural" and not args:  # plural(id, n) formats with n
                args = [count]
        wanted = max(need, default=0)
        if len(args) != wanted and not (fn in ("getString", "getQuantityString") and not args and wanted):
            yield line, f"{name} has {wanted} placeholder(s) but gets {len(args)} argument(s)"
        for k, c in need.items():
            if c == "d" and k <= len(args) and NOT_A_NUMBER.search(args[k - 1]):
                yield line, f"{name}: %{k}$d gets text: {args[k - 1]}"


def main():
    top = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip())
    formats = placeholders(top)
    found = 0
    for root in ROOTS:
        for path in sorted((top / root).rglob("*.kt")):
            rel = path.relative_to(top).as_posix()
            src = path.read_text(encoding="utf-8")
            for line, problem in check_uses(src, formats):
                print(f"::error file={rel},line={line}::{problem}")
                found += 1
            for line, text, before in literals(src):
                words = text.replace("{}", " ")
                if not (WORDS.search(words) or CAPITALISED.match(text) or TEMPLATE_WORD.search(text)):
                    continue
                if DEVELOPER.search(call_before(before)) or TO_STRING.search(before):
                    continue
                if any(re.search(f, rel) and re.fullmatch(t, text) for f, t in ALLOWED):
                    continue
                print(f"::error file={rel},line={line}::Text in code, not in strings.xml: \"{text}\"")
                found += 1
    if found:
        print(f"{found} problem(s). Text for people goes in res/values/strings.xml; a literal that must stay "
              "goes on the allowlist in scripts/check-strings.py with the reason.", file=sys.stderr)
        sys.exit(1)
    print("No text for people in Kotlin code; string resources get the arguments they take.")


if __name__ == "__main__":
    main()

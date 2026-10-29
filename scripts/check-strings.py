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

And it checks that a UiText (from a function declared to return one) never
reaches text unresolved: inside a string template, next to a string with +,
or as an argument of stringResource, getString and the like, which would
show the object instead of the words. Call .asString() or resolve() there.
The same goes for a list that holds a UiText next to text, or a list of
UiText joined with joinToString: use joined(), or resolve each one first.

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
    (r"core/ProblemText\.kt", r"insufficient space .*|no space left on device|folder marker missing|folder path missing"
                              r"|folder path not a directory|permission denied|peers who had this file went away"
                              r"|no connected device has the required version|file modified but not rescanned|Failed initial scan"),
    (r"ui/LiveStats\.kt", r"network is unreachable"),
    # A directory name on disk, made before the user picks one; not shown as text.
    (r"ui/Validation\.kt", r"Folder"),
    # Names of libraries and licences.
    (r"ui/AboutScreen\.kt", r"Apache-2\.0|Apache License 2\.0|Kotlin, kotlinx\..*|OkHttp, Okio"),
    # Date patterns, made locale-aware separately.
    (r"ui/Format\.kt", r"d MMM( yyyy)?(, HH:mm)?"),
    # HTTP protocol words.
    (r"ui/WebGuiScreen\.kt", r"Basic |Authorization|Forbidden"),
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


def masked(src):
    """[src] with comments and the text of string literals blanked (template code kept), and the template code spans."""
    out, spans, i, n = list(src), [], 0, len(src)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    def string(i):
        """i is just after the opening quote; returns the index after the closing one."""
        while i < n:
            c = src[i]
            if c == "\\":
                blank(i, i + 2); i += 2; continue
            if c == '"':
                return i + 1
            if c == "$" and src.startswith("${", i):
                blank(i, i + 2)
                end = code(i + 2)
                spans.append((i + 2, end - 1)); blank(end - 1, end)
                i = end; continue
            blank(i, i + 1); i += 1
        return i

    def code(i):
        """Code up to the brace closing an open one; returns the index after it."""
        depth = 1
        while i < n:
            if src.startswith('"""', i):
                j = src.index('"""', i + 3) + 3; blank(i, j); i = j; continue
            c = src[i]
            if c == '"':
                i = string(i + 1); continue
            depth += c == "{"
            depth -= c == "}"
            if depth == 0:
                return i + 1
            i += 1
        return i

    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i); j = n if j < 0 else j
            blank(i, j); i = j; continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2) + 2; blank(i, j); i = j; continue
        if src.startswith('"""', i):
            j = src.index('"""', i + 3) + 3
            while j < n and src[j] == '"':
                j += 1
            blank(i, j); i = j; continue
        c = src[i]
        if c == "'":
            j = src.index("'", i + 2 if src[i + 1] == "\\" else i + 1) + 1; blank(i, j); i = j; continue
        if c == '"':
            i = string(i + 1); continue
        i += 1
    return "".join(out), spans


# Calls that turn their arguments into text with toString().
FORMATTING = re.compile(r"\b(stringResource|pluralStringResource|getString|getQuantityString|format|append)\s*\($")
RESOLVED = re.compile(r"\s*\??\.(asString|resolve|en)\(")
# Functions and constructors that make a UiText.
MAKERS = {"text", "plural", "raw", "num", "time", "joined"}


def uitext_functions(sources):
    """Names of top-level functions declared to return UiText, or written as one of the [MAKERS]."""
    names = set(MAKERS)
    for src in sources:
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(", src):
            end = closing(src, m.end() - 1)
            rest = src[end + 1:end + 200]
            if re.match(r"\s*:\s*UiText\b", rest) or re.match(r"\s*=\s*(?:" + "|".join(MAKERS) + r")\(", rest):
                names.add(m.group(1))
    return names


def check_unresolved(src, names):
    """Lines where a UiText from [names] is turned into text without being resolved."""
    code, spans = masked(src)
    for m in re.finditer(r"(?<![\w.])(" + "|".join(sorted(names)) + r")\(", code):
        if re.search(r"\bfun\s+(?:[\w.<>?]+\.)?$", code[max(0, m.start() - 60):m.start()]):
            continue
        end = closing(code, m.end() - 1) + 1
        if RESOLVED.match(code, end):
            continue
        line = src.count("\n", 0, m.start()) + 1
        if any(a <= m.start() < b for a, b in spans):
            yield line, f"{m.group(1)}(...) in a string template shows the object, not the words"
        elif re.search(r'"\s*\+\s*$', src[:m.start()]) or re.match(r'\s*\+\s*"', src[end:]):
            yield line, f"{m.group(1)}(...) added to a string shows the object, not the words"
        elif FORMATTING.search(call_before(code[max(0, m.start() - 400):m.start()])):
            yield line, f"{m.group(1)}(...) as a formatting argument shows the object, not the words"


# Builders whose elements a joinToString later turns into text with toString().
COLLECTION = re.compile(r"(?<![\w.])(listOf|listOfNotNull|setOf|arrayOf|mutableListOf|sequenceOf)\s*\(")
FILTERING = re.compile(r"\s*\??\.(takeIf|takeUnless)\s*\{")
JOIN = re.compile(r"\s*\.joinToString\(")


def is_uitext(arg, names):
    """Whether the expression is a call to one of [names], at most followed by takeIf { } or takeUnless { }."""
    m = re.match(r"(" + "|".join(sorted(names)) + r")\(", arg)
    if not m:
        return False
    end = closing(arg, m.end() - 1) + 1
    while end < len(arg) and (f := FILTERING.match(arg, end)):
        end = closing(arg, f.end() - 1) + 1
    return not arg[end:].strip()


# An expression that is surely text: a literal, a resolved UiText, or a string resource.
TEXT = re.compile(r'^"|\??\.asString\(\)$|^(stringResource|pluralStringResource|getString|getQuantityString)\('
                  r'|\{\s*(stringResource|pluralStringResource)\(.*\}$', re.S)


def check_collections(src, names):
    """Lines where a list holding a UiText is joined with joinToString, or holds one next to text."""
    code, _ = masked(src)
    for m in COLLECTION.finditer(code):
        end = closing(code, m.end() - 1)
        args = arguments(code[m.end():end])
        if not any(is_uitext(a, names) for a in args):
            continue
        line = src.count("\n", 0, m.start()) + 1
        if (j := JOIN.match(code, end + 1)):
            after = closing(code, j.end() - 1) + 1
            if not re.match(r"\s*\{", code[after:]) and "transform" not in code[j.end():after]:
                yield line, f"{m.group(1)}(...) holds a UiText; joinToString shows the object, use joined() or resolve each one"
                continue
        if any(TEXT.search(a) for a in args):
            yield line, f"{m.group(1)}(...) mixes a UiText with text; joined, it shows the object, not the words"


def main():
    top = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip())
    formats = placeholders(top)
    files = [p for root in ROOTS for p in sorted((top / root).rglob("*.kt"))]
    names = uitext_functions(p.read_text(encoding="utf-8") for p in files)
    found = 0
    for path in files:
        rel = path.relative_to(top).as_posix()
        src = path.read_text(encoding="utf-8")
        problems = list(check_uses(src, formats)) + list(check_unresolved(src, names)) + list(check_collections(src, names))
        for line, problem in problems:
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

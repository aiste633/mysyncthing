#!/usr/bin/env bash
# Fails if the repository or its history contains personal network or machine details:
# IPv4 addresses other than loopback/any, home directories, Syncthing device IDs,
# e-mail addresses other than no-reply ones, and commit identities that are not no-reply.
# Scans every commit (diffs and messages), so a leak added and removed again is found too.
#
# Usage:  scripts/check-leaks.sh [<rev-range>]     (default: all of HEAD's history)
# Optional: LEAK_DENYLIST, one private term per line (hostnames, user names, IP prefixes).
# Findings are masked and named by commit, so a public CI log does not repeat the leak.
set -euo pipefail

range="${1:-HEAD}"
cd "$(git rev-parse --show-toplevel)"

# Checksums and version numbers there look like IPs; the submodule is upstream's code.
pathspec=(. ':(exclude)gradle/verification-metadata.xml' ':(exclude)scripts/check-leaks.sh')

ipv4='\b([0-9]{1,3}\.){3}[0-9]{1,3}\b'
home='(/home/|/Users/|[A-Za-z]:\\\\Users\\\\)[A-Za-z0-9._-]+'
device_id='\b([A-Z2-7]{7}-){7}[A-Z2-7]{7}\b'
email='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
allowed_ip='^(127\.0\.0\.1|0\.0\.0\.0)$'
allowed_email='(@users\.noreply\.github\.com|^noreply@[a-z.]+|@example\.(com|org))$'

found=0
report() { echo "::error::$1"; found=1; }

# Added lines and commit messages, with the commit each came from.
added="$(git log --format='commit %h%n%B' -p --no-color --no-ext-diff "$range" -- "${pathspec[@]}" \
  | awk '/^commit [0-9a-f]+$/ { c = $2; next } /^\+\+\+ / { next } /^\+/ || !/^[-@ ]|^diff |^index |^new |^deleted / { print c ": " $0 }')"

candidates="$(grep -E "$ipv4|$home|$device_id|$email" <<<"$added" || true)"
while IFS= read -r line; do
  [ -n "$line" ] || continue
  commit="${line%%:*}"
  text="${line#*: }"
  for ip in $(grep -oE "$ipv4" <<<"$text" || true); do
    grep -qE "$allowed_ip" <<<"$ip" || report "$commit: IP address ${ip%%.*}.x.x.x"
  done
  for h in $(grep -oE "$home" <<<"$text" || true); do report "$commit: home path ${h%/*}/…"; done
  for d in $(grep -oE "$device_id" <<<"$text" || true); do report "$commit: Syncthing device ID"; done
  for e in $(grep -oE "$email" <<<"$text" || true); do
    grep -qE "$allowed_email" <<<"$e" || report "$commit: e-mail address …@${e#*@}"
  done
done <<<"$candidates"

while read -r commit author committer; do
  for e in "$author" "$committer"; do
    grep -qE "$allowed_email" <<<"$e" || report "$commit: commit identity …@${e#*@} is not a no-reply address"
  done
done < <(git log --format='%h %ae %ce' "$range")

if [ -n "${LEAK_DENYLIST:-}" ]; then
  while IFS= read -r term; do
    [ -n "$term" ] || continue
    hits="$({ grep -iF -- "$term" <<<"$added" || true; } | cut -d: -f1 | sort -u | tr '\n' ' ')"
    [ -z "$hits" ] || report "a private term from LEAK_DENYLIST appears in: $hits"
  done <<<"$LEAK_DENYLIST"
fi

[ "$found" -eq 0 ] && echo "No personal details found."
exit "$found"

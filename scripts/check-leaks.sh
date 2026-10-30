#!/usr/bin/env bash
# Fails if the repository or its history contains anything that looks like a personal
# network or machine detail and is not on the allowlist below. Nothing is denylisted:
# every address, host, path, ID and e-mail found must be a known public host or a
# placeholder, so new or renamed machines are caught without updating anything.
# Scans every commit (added lines and messages) and every commit identity, so a leak
# that was added and removed again is found too.
#
# Usage: scripts/check-leaks.sh [<rev-range>]     (default: all of HEAD's history)
# Findings are masked and named by commit, so a public CI log does not repeat the leak.
set -euo pipefail

range="${1:-HEAD}"
cd "$(git rev-parse --show-toplevel)"

# Checksum files: their versions and hashes look like addresses.
pathspec=(. ':(exclude)gradle/verification-metadata.xml' ':(exclude)go/bridge/go.sum' ':(exclude)scripts/check-leaks.sh')

# --- Allowlist: public hosts (a host matches itself and its subdomains) and placeholders.
public_hosts=(
  github.com githubusercontent.com gitlab.com f-droid.org
  syncthing.net
  android.com google.com gradle.org maven.org
  anthropic.com claude.ai claude.com
  gnu.org fsf.org apache.org mozilla.org debian.org
  osv.dev golang.org pypi.org
  example.com example.org example.net
)
# Loopback, any, and the documentation ranges of RFC 5737 / RFC 3849.
allowed_ip='^(127\.0\.0\.1|0\.0\.0\.0|192\.0\.2\.[0-9]+|198\.51\.100\.[0-9]+|203\.0\.113\.[0-9]+|::1?|2001:db8:.*)$'
# Also Kotlin's labelled this (this@SomeClass.member), which looks like an address.
allowed_email='^(noreply@[a-z.]+|[0-9]+\+[A-Za-z0-9-]+@users\.noreply\.github\.com|[^@]+@example\.(com|org|net)|this@[A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*)$'
allowed_host='^(localhost|127\.0\.0\.1|\$\{.*\}|<.*>)$'

# --- What counts as a personal detail.
ipv4='\b([0-9]{1,3}\.){3}[0-9]{1,3}\b'
ipv6='\b([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b|\b([0-9a-fA-F]{1,4}:)+:([0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4})*)?'
home='(/home/|/Users/|/root/|[A-Za-z]:\\Users\\)[A-Za-z0-9._-]+'
device_id='\b([A-Z2-7]{7}-){7}[A-Z2-7]{7}\b'
email='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
url_host='\b[a-zA-Z][a-zA-Z0-9+.-]*://[^/ "'"'"'<>()`]+'
host_port='\b[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z0-9-]+)*:[0-9]{2,5}\b'
lan_name='\b[a-z0-9-]+(\.[a-z0-9-]+)*\.(lan|local|home|internal|localdomain|intranet|corp|fritz\.box|ts\.net)\b'
unc='\\\\[A-Za-z0-9._-]+\\[A-Za-z0-9$._-]+'
# user@host without a domain, as in a shell prompt or scp target.
user_at_host='\b[a-z_][a-z0-9_-]*@[A-Za-z][A-Za-z0-9-]*\b[^.]'
# Kotlin labels, GitHub Actions pinned by commit (action@<40 hex>), and the placeholder itself.
user_at_host_ok='^((return|this|break|continue|super)@|[a-z0-9_-]+@[0-9a-f]{40}\b|user@host\b)'

found=0
report() { echo "::error::$1"; found=1; }

is_public_host() {
  local h="${1,,}" p
  [[ "$h" =~ $allowed_host ]] && return 0
  for p in "${public_hosts[@]}"; do
    [[ "$h" == "$p" || "$h" == *".$p" ]] && return 0
  done
  return 1
}

# Added lines and commit messages, each prefixed with its commit.
added="$(git log --format='commit %h%n%B' -p --no-color --no-ext-diff "$range" -- "${pathspec[@]}" \
  | awk '/^commit [0-9a-f]+$/ { c = $2; next } /^\+\+\+ / { next } /^\+/ || !/^[-@ ]|^diff |^index |^new |^deleted / { print c ": " $0 }')"

any="$ipv4|$ipv6|$home|$device_id|$email|$url_host|$host_port|$lan_name|$unc|$user_at_host"
candidates="$(grep -E "$any" <<<"$added" || true)"

while IFS= read -r line; do
  [ -n "$line" ] || continue
  commit="${line%%:*}"
  text="${line#*: }"
  matches() { grep -oE "$1" <<<"$text" || true; }

  for ip in $(matches "$ipv4") $(matches "$ipv6"); do
    [[ "$ip" =~ $allowed_ip ]] || report "$commit: IP address (${#ip} characters)"
  done
  [ -z "$(matches "$home")" ] || report "$commit: home path"
  [ -z "$(matches "$device_id")" ] || report "$commit: Syncthing device ID"
  for e in $(matches "$email"); do
    [[ "$e" =~ $allowed_email ]] || report "$commit: e-mail address …@${e#*@}"
  done
  for u in $(matches "$url_host"); do
    h="${u#*://}"; h="${h##*@}"; h="${h%:*}"
    [[ "$h" =~ $ipv4 ]] && continue # reported above
    is_public_host "$h" || report "$commit: host in a URL that is not on the public allowlist"
  done
  for hp in $(matches "$host_port"); do
    h="${hp%:*}"
    [[ "$text" == *"://$hp"* || "$text" == *"@$hp"* ]] && continue # URL hosts are checked above
    is_public_host "$h" || report "$commit: host:port that is not on the public allowlist"
  done
  for n in $(matches "$lan_name"); do
    [ "$n" = jdk.internal ] && continue # the JDK's own packages, named in JVM flags
    report "$commit: local network name"
  done
  [ -z "$(matches "$unc")" ] || report "$commit: Windows network path"
  for ua in $(matches "$user_at_host"); do
    [[ "$ua" =~ $user_at_host_ok ]] || report "$commit: user@host"
  done
done <<<"$candidates"

while read -r commit author committer; do
  for e in "$author" "$committer"; do
    [[ "$e" =~ $allowed_email ]] || report "$commit: commit identity …@${e#*@} is not a no-reply address"
  done
done < <(git log --format='%h %ae %ce' "$range")

[ "$found" -eq 0 ] && echo "No personal details found."
exit "$found"

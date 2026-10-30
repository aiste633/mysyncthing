#!/usr/bin/env bash
# Checks that F-Droid keeps up with our releases, for the upkeep workflow
# (.github/workflows/upkeep.yml). Once the app is in fdroiddata, F-Droid builds each new tag
# from its own copy of the recipe: its bot copies the last build entry and changes only the
# version and commit. So:
#  1. fdroiddata's recipe must match ours, apart from versions. A changed NDK, JDK, Go
#     bootstrap or build step must reach fdroiddata before the next tag, or F-Droid's APK no
#     longer matches ours, cannot carry our signature, and that version is never published.
#  2. F-Droid must have published our latest release within 10 days.
# Both are skipped, with a note on stderr, while the app is not in fdroiddata or on F-Droid.
#
# Usage:  scripts/upkeep/fdroid.sh [REPO_ROOT]
#         Prints Markdown and exits 0 (in step), 1 (found something) or else (a check failed).
# Needs:  curl, jq, yq (mikefarah, v4); gh with GH_TOKEN and GH_REPO for the latest release.
# Env:    FDROIDDATA_RECIPE, FDROID_API: other URLs, for tests.
set -euo pipefail

root="${1:-.}"
app=eu.stedb.mysyncthing
recipe_url="${FDROIDDATA_RECIPE:-https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/$app.yml}"
api_url="${FDROID_API:-https://f-droid.org/api/v1/packages/$app}"
grace_days=10
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Prints the HTTP status; the body goes to $2. Fails on anything but an answer.
get() {
  curl -sS --retry 3 --retry-all-errors --max-time 60 -o "$2" -w '%{http_code}' "$1"
}

# The recipe as F-Droid's bot sees it: no comments, no versions, only the last build entry.
build_recipe() {
  yq -o=json "$1" | jq -S 'del(.CurrentVersion, .CurrentVersionCode)
    | .Builds = (.Builds[-1] | del(.versionName, .versionCode, .commit))'
}

found=0

status="$(get "$recipe_url" "$work/fdroiddata.yml")"
case "$status" in
  200)
    build_recipe "$root/fdroid/$app.yml" > "$work/ours.json"
    build_recipe "$work/fdroiddata.yml" > "$work/theirs.json"
    if ! diff -u --label fdroiddata --label ours "$work/theirs.json" "$work/ours.json" > "$work/recipe.diff"; then
      found=1
      cat <<EOF
### F-Droid recipe out of step

fdroiddata's recipe differs from \`fdroid/$app.yml\`, apart from versions. F-Droid builds the
next tag with fdroiddata's, so its APK would not match ours and that version would not be
published. Send fdroiddata a merge request with our recipe before the next tag.

\`\`\`diff
$(cat "$work/recipe.diff")
\`\`\`

EOF
    fi ;;
  404) echo "fdroid.sh: $app is not in fdroiddata yet; recipe not compared." >&2 ;;
  *) echo "fdroid.sh: fdroiddata answered HTTP $status" >&2; exit 2 ;;
esac

status="$(get "$api_url" "$work/fdroid.json")"
case "$status" in
  200)
    release="$(gh release view --json tagName,publishedAt --jq '[.tagName, .publishedAt] | @tsv')"
    tag="${release%%$'\t'*}"
    published="${release#*$'\t'}"
    age=$(( ($(date -u +%s) - $(date -u -d "$published" +%s)) / 86400 ))
    if ! jq -e --arg v "${tag#v}" 'any(.packages[]; .versionName == $v)' "$work/fdroid.json" > /dev/null \
        && [ "$age" -ge "$grace_days" ]; then
      found=1
      newest="$(jq -r '[.packages[].versionName] | first // "none"' "$work/fdroid.json")"
      cat <<EOF
### F-Droid has not published $tag

$tag was released $age days ago; F-Droid's newest is $newest. Its build log for the version
shows why: https://monitor.f-droid.org/builds (search for \`$app\`). Usually the recipe is out
of step, or F-Droid's build did not match our APK.

EOF
    fi ;;
  404) echo "fdroid.sh: $app is not on F-Droid yet; releases not compared." >&2 ;;
  *) echo "fdroid.sh: F-Droid's API answered HTTP $status" >&2; exit 2 ;;
esac

exit "$found"

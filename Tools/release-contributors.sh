#!/usr/bin/env bash
# release-contributors.sh — collect THIRD-PARTY contributor handles for a release's credit line (#736).
#
# Release notes used to credit people by display name ("FF", "Pipiche"), which GitHub cannot resolve: the
# contributor is thanked visibly but never notified, and the credit links nowhere. Getting the handles right
# by hand means cross-referencing every merged PR and closed issue in the range, which is exactly the sort
# of chore that silently drifts. This does the mechanical half.
#
# It prints each third-party @handle with the PRs they landed and the issues they reported, so the release
# author only has to write WHAT each person contributed — the judgement part — instead of hunting logins.
#
# The maintainer's own handles are excluded on purpose: self-credit is noise, and a self-mention notifies
# nobody. Override with MAINTAINERS="a,b" if the set ever changes.
#
# Usage:
#   Tools/release-contributors.sh 2026-07-20              # since a date (inclusive)
#   Tools/release-contributors.sh v9.0.2                  # since a tag's commit date
#   MAINTAINERS="ryanbr,Fanboynz" Tools/release-contributors.sh 2026-07-20
#
# Requires: gh (authenticated), jq-free (uses gh's --jq).
set -euo pipefail

SINCE_INPUT="${1:-}"
if [ -z "$SINCE_INPUT" ]; then
  echo "usage: $(basename "$0") <since-date|since-tag>" >&2
  exit 2
fi

# A tag resolves to its commit date; a date is used as-is.
if git rev-parse -q --verify "refs/tags/$SINCE_INPUT" >/dev/null 2>&1; then
  SINCE="$(git log -1 --format=%cs "refs/tags/$SINCE_INPUT")"
  echo "# since tag $SINCE_INPUT ($SINCE)"
else
  SINCE="$SINCE_INPUT"
  echo "# since $SINCE"
fi

MAINTAINERS="${MAINTAINERS:-ryanbr,Fanboynz}"
# Field-exact, case-insensitive filter. The listings are "handle<TAB>#N<TAB>title", so a whole-line
# anchor would never match and the maintainer would leak into the output (caught by running it);
# compare only field 1. The bare-login list below uses an exact whole-line match instead.
drop_maintainers_field1() {
  awk -F'\t' -v ex="$MAINTAINERS" '
    BEGIN { n = split(tolower(ex), m, ",") }
    { h = tolower($1); skip = 0
      for (i = 1; i <= n; i++) if (h == m[i]) skip = 1
      if (!skip) print }'
}
EXCLUDE_EXACT="^($(printf '%s' "$MAINTAINERS" | tr ',' '|'))$"

REPO="${GH_REPO:-ryanbr/noop}"

echo
echo "## Merged PRs by third-party contributors"
gh pr list --repo "$REPO" --state merged --limit 300 \
  --search "merged:>=$SINCE" --json number,author,title \
  --jq '.[] | "\(.author.login)\t#\(.number)\t\(.title)"' 2>/dev/null \
  | drop_maintainers_field1 \
  | sort -f || true

echo
echo "## Issues reported by third-party contributors (closed in range)"
gh issue list --repo "$REPO" --state closed --limit 300 \
  --search "closed:>=$SINCE" --json number,author,title \
  --jq '.[] | "\(.author.login)\t#\(.number)\t\(.title)"' 2>/dev/null \
  | drop_maintainers_field1 \
  | sort -f || true

echo
echo "## Credit line (add what each person contributed, then paste into the release notes)"
{
  gh pr list --repo "$REPO" --state merged --limit 300 --search "merged:>=$SINCE" \
    --json author --jq '.[].author.login' 2>/dev/null || true
  gh issue list --repo "$REPO" --state closed --limit 300 --search "closed:>=$SINCE" \
    --json author --jq '.[].author.login' 2>/dev/null || true
} | grep -viE "$EXCLUDE_EXACT" | sort -uf \
  | awk 'BEGIN { ORS="" } { if (NR > 1) print ", "; print "@" $0 } END { print "\n" }' \
  | sed 's/^/Thanks to /'

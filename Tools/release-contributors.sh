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
#   Tools/release-contributors.sh 2026-07-20              # since a date (inclusive, whole day)
#   Tools/release-contributors.sh v9.0.2                  # since that tag's exact commit time
#   MAINTAINERS="ryanbr,Fanboynz" Tools/release-contributors.sh 2026-07-20
#
# Requires: gh, authenticated. A dead or unauthenticated gh is a hard error, never an empty list —
# for a tool whose whole job is "nobody is missed", silently missing EVERYBODY is the worst failure mode.
set -euo pipefail

SINCE_INPUT="${1:-}"
if [ -z "$SINCE_INPUT" ]; then
  echo "usage: $(basename "$0") <since-date|since-tag>" >&2
  exit 2
fi

command -v gh >/dev/null 2>&1 || { echo "error: gh not found on PATH" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "error: gh is not authenticated (run: gh auth login)" >&2; exit 1; }

# A tag resolves to its exact commit TIME, not just its date. A date-only bound is inclusive of the whole
# day, so it re-credits everything merged earlier on release day — work that shipped in the PREVIOUS
# release. On v9.0.2 (tagged 06:15Z) that was 14 PRs. GitHub search honours an ISO8601 instant, so use one.
if git rev-parse -q --verify "refs/tags/$SINCE_INPUT" >/dev/null 2>&1; then
  SINCE="$(date -u -d "$(git log -1 --format=%cI "refs/tags/$SINCE_INPUT")" +%Y-%m-%dT%H:%M:%SZ)"
  echo "# since tag $SINCE_INPUT ($SINCE)"
else
  SINCE="$SINCE_INPUT"
  echo "# since $SINCE"
fi

MAINTAINERS="${MAINTAINERS:-ryanbr,Fanboynz}"
REPO="${GH_REPO:-ryanbr/noop}"
LIMIT=300

# Field-exact, case-insensitive. The rows are "handle<TAB>#N<TAB>title", so a whole-line anchor never
# matches and the maintainer leaks through; compare only field 1.
drop_maintainers() {
  awk -F'\t' -v ex="$MAINTAINERS" '
    BEGIN { n = split(tolower(ex), m, ",") }
    { h = tolower($1); skip = 0
      for (i = 1; i <= n; i++) if (h == m[i]) skip = 1
      if (!skip) print }'
}

# Titles are user-supplied and 3 of this repo's issues contain a newline or tab (e.g. #717). Left raw they
# split one record across several lines, and a continuation line has no handle in field 1 — so a
# MAINTAINER-authored issue leaks its title fragments into the third-party listing. Flatten first.
JQ_ROW='.[] | "\(.author.login)\t#\(.number)\t\(.title | gsub("[\r\n\t]+"; " "))"'

# Only COMPLETED issues. A report closed as not-planned or duplicate did not drive a fix, and crediting it
# is the same kind of noise the handle convention exists to remove.
prs="$(gh pr list --repo "$REPO" --state merged --limit "$LIMIT" \
        --search "merged:>=$SINCE" --json number,author,title --jq "$JQ_ROW")"
issues="$(gh issue list --repo "$REPO" --state closed --limit "$LIMIT" \
        --search "closed:>=$SINCE" --json number,author,title,stateReason \
        --jq '[.[] | select(.stateReason == "COMPLETED")] | '"$JQ_ROW")"

# A silent top-N cap would read as "that is everyone" when it is not.
warn_if_truncated() {   # warn_if_truncated <what> <rows>
  if [ -n "$2" ] && [ "$(printf '%s\n' "$2" | wc -l)" -ge "$LIMIT" ]; then
    echo "# warning: hit the $LIMIT-item limit for $1 — the range may be truncated" >&2
  fi
}
warn_if_truncated "pull requests" "$prs"
warn_if_truncated "issues" "$issues"

section() {   # section <heading> <rows>
  echo
  echo "$1"
  # Emptiness is judged AFTER filtering: rows that exist but are all the maintainer's still mean
  # "nothing to credit here", and a bare heading reads as a glitch rather than an answer.
  local kept; kept="$(printf '%s\n' "$2" | drop_maintainers | grep -v '^$' | sort -f || true)"
  if [ -z "$kept" ]; then echo "(none)"; else printf '%s\n' "$kept"; fi
}
section "## Merged PRs by third-party contributors"                       "$prs"
section "## Issues reported by third-party contributors (closed as completed)" "$issues"

echo
echo "## Credit line (add what each person contributed, then paste into the release notes)"
# Derived from the rows already fetched, so the line can never disagree with the listings above.
handles="$(printf '%s\n%s\n' "$prs" "$issues" | drop_maintainers | cut -f1 | grep -v '^$' | sort -uf || true)"
if [ -z "$handles" ]; then
  # Legitimate for a maintainer-only hotfix. Emitting a dangling "Thanks to" would be worse than saying so,
  # and exiting non-zero here (grep finding no match under pipefail) made the tool look broken.
  echo "(no third-party contributors in range — omit the credit section)"
else
  printf '%s\n' "$handles" \
    | awk 'BEGIN { ORS="" } { if (NR > 1) print ", "; print "@" $0 } END { print "\n" }' \
    | sed 's/^/Thanks to /'
fi

#!/bin/bash
# Catches the mechanical breakages that cost a full CI round trip: a stray annotation, a
# constructor whose test call sites were not updated, a default a test still asserts.
# Not a compiler. Run before pushing Kotlin changes: tools/precheck.sh [base-ref]
set -u
base="${1:-HEAD}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 1
fail=0

changed=$(git diff --name-only "$base" -- '*.kt'; git diff --cached --name-only "$base" -- '*.kt')
changed=$(printf '%s\n' $changed | sort -u)
[ -z "$changed" ] && { echo "precheck: no Kotlin changes"; exit 0; }

for f in $changed; do
  [ -f "$f" ] || continue
  # An annotation must sit on the declaration it annotates, not on a comment or another annotation.
  awk -v F="$f" '
    /^[[:space:]]*@Composable[[:space:]]*$/ { pending=NR; next }
    pending && /^[[:space:]]*(\/\/|\/\*|\*)/ { print F":"pending": @Composable separated from its declaration"; pending=0; next }
    pending { pending=0 }
  ' "$f"
done

# Classes whose primary constructor changed: every call site has to match, tests included.
for f in $changed; do
  [ -f "$f" ] || continue
  if git diff -U0 "$base" -- "$f" | grep -qE '^\+.*(private )?val [a-zA-Z]+: [A-Z][A-Za-z]+,?$'; then
    cls=$(grep -oE '^(internal |private )?(abstract )?class [A-Z][A-Za-z0-9_]+' "$f" | head -1 | awk '{print $NF}')
    [ -z "$cls" ] && continue
    sites=$(grep -rln "$cls(" --include='*.kt' . 2>/dev/null | grep -v '/build/' | grep "/src/test/\|/src/androidTest/")
    if [ -n "$sites" ]; then
      echo "precheck: $cls constructor touched, check these test call sites:"
      printf '  %s\n' $sites
      fail=1
    fi
  fi
done

# Values a test asserts on, changed in main source.
for f in $changed; do
  case "$f" in */src/test/*|*/src/androidTest/*) continue;; esac
  [ -f "$f" ] || continue
  for sym in $(git diff -U0 "$base" -- "$f" | grep -oE '^\-.*(val|const val) [a-zA-Z][A-Za-z0-9_]+' | awk '{print $NF}' | sort -u); do
    hits=$(grep -rln "\b$sym\b" --include='*.kt' . 2>/dev/null | grep -v '/build/' | grep "/src/test/")
    [ -n "$hits" ] && { echo "precheck: '$sym' changed and tests read it:"; printf '  %s\n' $hits; fail=1; }
  done
done

[ "$fail" -eq 0 ] && echo "precheck: nothing suspicious"
exit 0

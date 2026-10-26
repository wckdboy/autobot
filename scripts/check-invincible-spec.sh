#!/usr/bin/env bash
# Checks that the conformance vectors Autobot runs (copied by scripts/sync-invincible-spec.sh into
# the JVM test resources, and the same files for the Android device test) are Invincible's, byte
# for byte, in both copies. A rule that changed on the node's side then fails here, before a
# release, instead of in a user's app (receipts v1 went unnoticed for 86 commits).
#
#   scripts/check-invincible-spec.sh                  # Invincible's main on GitHub
#   scripts/check-invincible-spec.sh v0.0.22          # a tag, branch or commit there
#   scripts/check-invincible-spec.sh SOURCE           # the commit SOURCE says they came from
#   scripts/check-invincible-spec.sh ../invincible    # a checkout (its working tree)
#
# Exit status: 0 the same; 1 they differ (sync them, then run the JVM and iOS tests); 2 nothing
# could be compared. INVINCIBLE_REPO overrides the repository fetched from.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/shared/src/jvmTest/resources/invincible"
device_dest="$root/providers/remote/src/androidTest/assets/invincible"
repo="${INVINCIBLE_REPO:-https://github.com/wckdboy/invincible}"
target="${1:-main}"

[ -f "$dest/SOURCE" ] || { echo "no $dest/SOURCE: nothing was synced" >&2; exit 2; }
synced_from="$(cut -d' ' -f1 "$dest/SOURCE")"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ -d "$target" ]; then
  [ -d "$target/spec/vectors" ] || { echo "no spec/vectors in $target" >&2; exit 2; }
  vectors="$target/spec/vectors"
  against="$target ($(git -C "$target" rev-parse HEAD 2>/dev/null || echo "not a git checkout"))"
else
  [ "$target" = SOURCE ] && target="$synced_from"
  # One commit, shallow: about a megabyte.
  git -C "$work" init -q
  git -C "$work" fetch -q --depth 1 "$repo" "$target" || { echo "could not fetch $target from $repo" >&2; exit 2; }
  git -C "$work" archive FETCH_HEAD spec/vectors | tar -x -C "$work"
  vectors="$work/spec/vectors"
  against="$repo $target ($(git -C "$work" rev-parse FETCH_HEAD))"
fi

echo "Autobot's vectors (synced from $synced_from) against $against:"
status=0
# Every vector file the JVM tests or the Android device test runs, whichever copy has it: it must
# be in both, the same in both, and Invincible's. A file only one copy has fails as well, so a
# vector the device test runs is never left unchecked because the JVM tests lack it.
shopt -s nullglob
names="$(for f in "$dest"/*.json "$device_dest"/*.json; do basename "$f"; done | sort -u)"
[ -n "$names" ] || { echo "no vector files in $dest or $device_dest" >&2; exit 2; }
fail() { echo "  $name: $1"; status=1; ok=false; }
while read -r name; do
  jvm="$dest/$name"
  device="$device_dest/$name"
  ok=true
  [ -f "$jvm" ] || fail "only the Android device test has it, not the JVM tests"
  [ -f "$device" ] || fail "the Android device test has no copy"
  if [ -f "$jvm" ] && [ -f "$device" ] && ! cmp -s "$jvm" "$device"; then
    fail "the Android device test's copy differs from the JVM tests'"
  fi
  if [ ! -f "$vectors/$name" ]; then
    fail "Invincible has no such file"
  else
    if [ -f "$jvm" ] && ! cmp -s "$jvm" "$vectors/$name"; then fail "differs"; fi
    if [ -f "$device" ] && ! cmp -s "$device" "$vectors/$name"; then fail "the Android device test's copy differs from Invincible's"; fi
  fi
  if $ok; then echo "  $name: the same"; fi
done <<< "$names"
# Both copies come from the same commit.
if ! cmp -s "$dest/SOURCE" "$device_dest/SOURCE"; then
  echo "  SOURCE: the Android device test's differs from the JVM tests'"
  status=1
fi
# Vector files Autobot does not copy (rules the app does not implement): said, not failed.
others=()
for f in "$vectors"/*.json; do
  name="$(basename "$f")"
  [ -f "$dest/$name" ] || [ -f "$device_dest/$name" ] || others+=("$(basename "$f" .json)")
done
[ ${#others[@]} -eq 0 ] || echo "  not copied (the app has no such rule): ${others[*]}"

if [ "$status" -ne 0 ]; then
  echo "They differ. With an Invincible checkout at that commit: scripts/sync-invincible-spec.sh <checkout>," >&2
  echo "then the JVM tests (InvincibleSpecTest) and the iOS tests (InvincibleNodeTests)." >&2
fi
exit "$status"

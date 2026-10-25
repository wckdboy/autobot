#!/usr/bin/env bash
# Checks that the conformance vectors Autobot runs (copied by scripts/sync-invincible-spec.sh into
# the JVM test resources, and the same files for the Android device test) are Invincible's, byte
# for byte. A rule that changed on the node's side then fails here, before a release, instead of
# in a user's app (receipts v1 went unnoticed for 86 commits).
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
for f in "$dest"/*.json; do
  name="$(basename "$f")"
  if [ ! -f "$vectors/$name" ]; then
    echo "  $name: Invincible has no such file"
    status=1
  elif cmp -s "$f" "$vectors/$name"; then
    echo "  $name: the same"
  else
    echo "  $name: differs"
    status=1
  fi
done
# The Android device test runs its own copy: it must be the same files, from the same commit.
for f in "$dest"/*.json "$dest/SOURCE"; do
  if ! cmp -s "$f" "$device_dest/$(basename "$f")"; then
    echo "  $(basename "$f"): the Android device test's copy differs from the JVM tests'"
    status=1
  fi
done
# Vector files Autobot does not copy (rules the app does not implement): said, not failed.
others=()
for f in "$vectors"/*.json; do
  [ -f "$dest/$(basename "$f")" ] || others+=("$(basename "$f" .json)")
done
[ ${#others[@]} -eq 0 ] || echo "  not copied (the app has no such rule): ${others[*]}"

if [ "$status" -ne 0 ]; then
  echo "They differ. With an Invincible checkout at that commit: scripts/sync-invincible-spec.sh <checkout>," >&2
  echo "then the JVM tests (InvincibleSpecTest) and the iOS tests (InvincibleNodeTests)." >&2
fi
exit "$status"

#!/usr/bin/env bash
# Copies the conformance vectors Autobot shares with Invincible (github.com/wckdboy/invincible,
# spec/vectors) into the JVM test resources, and records which Invincible commit they came from.
# Both projects run the same files; a rule that changes on one side fails the other's tests until
# both agree.
#
#   scripts/sync-invincible-spec.sh [path to an invincible checkout, default ../invincible]
set -euo pipefail

src="${1:-$(dirname "$0")/../../invincible}"
dest="$(dirname "$0")/../shared/src/jvmTest/resources/invincible"
files=(pairing-code noise receipts redaction)

[ -d "$src/spec/vectors" ] || { echo "no spec/vectors in $src" >&2; exit 1; }
mkdir -p "$dest"
for f in "${files[@]}"; do cp "$src/spec/vectors/$f.json" "$dest/$f.json"; done
commit="$(git -C "$src" rev-parse HEAD)"
dirty="$(git -C "$src" status --porcelain -- spec/vectors | head -1)"
echo "$commit${dirty:+ (with uncommitted changes)}" > "$dest/SOURCE"
echo "copied ${files[*]} from invincible $commit${dirty:+ (with uncommitted changes)}"

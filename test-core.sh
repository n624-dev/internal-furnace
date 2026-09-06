#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mapfile -t sources < <(find "$root/src/main/java/dev/n624/internalfurnace/core" "$root/src/test/java" -name '*.java' -type f | sort)
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$work" "${sources[@]}"
java -ea -cp "$work" dev.n624.internalfurnace.core.CoreTests "$root/src/main/resources/data/internal_furnace/upgrades.psv"
java -ea -cp "$work" dev.n624.internalfurnace.core.EditorTests

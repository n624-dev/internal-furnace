#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$root/.deps"
fetch() {
    local name=$1 digest=$2 project=$3 file=$4 target="$root/.deps/$1"
    if [[ -f "$target" && ! -L "$target" ]] && printf '%s  %s\n' "$digest" "$target" | sha256sum --check --status; then return; fi
    local temp
    temp=$(mktemp "$root/.deps/download.XXXXXX")
    if ! curl --fail --location --retry 3 --user-agent 'internal-furnace-build/0.1' \
        "https://www.curseforge.com/api/v1/mods/$project/files/$file/download" -o "$temp"; then
        rm -f -- "$temp"; echo "Download failed: place the exact $name in .deps and retry." >&2; return 1
    fi
    if ! printf '%s  %s\n' "$digest" "$temp" | sha256sum --check --status; then
        rm -f -- "$temp"; echo "Dependency SHA-256 mismatch: $name" >&2; return 1
    fi
    mv -f -- "$temp" "$target"
}
fetch Mine_and_Slash-1.20.1-6.4.7.jar f97a91db0ae360e50ab1f976c152b79078255bc3cea5152032b80a6385870dc0 306575 8619237
fetch Library_of_Exile-1.20.1-2.1.11.jar faeee436faada4aa21ed80690e0767a46962eee31e7bcfa58328dbf9717af0be 398780 8619239

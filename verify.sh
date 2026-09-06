#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
version=$(sed -n 's/^mod_version=//p' "$root/gradle.properties")
jar="$root/build/libs/internal-furnace-$version.jar"
[[ -f "$jar" && ! -L "$jar" ]] || { echo "Missing artifact: $jar" >&2; exit 1; }
python3 - "$jar" <<'PYTHON'
import sys, zipfile, tomllib
from pathlib import PurePosixPath
with zipfile.ZipFile(sys.argv[1]) as jar:
    names=jar.namelist()
    assert len(names)==len(set(names)), 'Duplicate ZIP entries'
    assert all(not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts for n in names)
    for required in ('META-INF/mods.toml', 'pack.mcmeta', 'LICENSE',
                     'dev/n624/internalfurnace/InternalFurnace.class',
                     'dev/n624/internalfurnace/client/FurnaceScreen.class',
                     'dev/n624/internalfurnace/forge/FurnaceData.class',
                     'data/internal_furnace/upgrades.psv',
                     'assets/internal_furnace/lang/ja_jp.json'):
        assert required in names, required
    assert not any(n.startswith('com/robertx22/') or n.endswith('CoreTests.class') or '/internalfurnace/test/' in n for n in names), 'Dependencies/tests must not be shaded'
    metadata=tomllib.loads(jar.read('META-INF/mods.toml').decode())
    assert metadata['license']=='MIT'
    assert metadata['loaderVersion']=='[47,48)'
    assert metadata['mods'][0]['modId']=='internal_furnace'
    for d in metadata['dependencies']['internal_furnace']:
        assert d['mandatory'] and d['side']=='BOTH'
    assert metadata['dependencies']['internal_furnace'][0]['versionRange']=='[47.4.10]'
print('PASS: artifact structure (not a runtime/connection test)')
PYTHON
sha256sum "$jar"

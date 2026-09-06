#!/usr/bin/env python3
"""Check paired docs, metadata, translations and the published Gradle wrapper."""
import hashlib
import json
import re
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = re.search(r'^mod_version=(.+)$', (root / 'gradle.properties').read_text(), re.M).group(1)
assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version), 'Invalid mod_version'
meta = tomllib.loads((root / 'src/main/resources/META-INF/mods.toml').read_text())
assert meta['license'] == 'MIT' and meta['mods'][0]['modId'] == 'internal_furnace'
assert meta['loaderVersion'] == '[47,48)'
lang = root / 'src/main/resources/assets/internal_furnace/lang'
a, b = (json.loads((lang / f'{locale}.json').read_text()) for locale in ['en_us', 'ja_jp'])
assert a.keys() == b.keys(), 'Translation keys differ'
assert all(a.values()) and all(b.values()), 'Empty translations'
assert hashlib.sha256((root / 'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest() == 'ed2c26eba7cfb93cc2b7785d05e534f07b5b48b5e7fc941921cd098628abca58'
assert 'distributionSha256Sum=e111cb9948407e26351227dabce49822fb88c37ee72f1d1582a69c68af2e702f' in (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
docs = list(root.glob('*.md')) + list((root / 'docs').glob('*.md'))
for p in docs:
    if p.name != 'AGENTS.md':
        counterpart = p.with_name(p.name.replace('.ja.md', '.md') if p.name.endswith('.ja.md') else p.stem + '.ja.md')
        assert counterpart.is_file(), f'Missing bilingual document: {counterpart}'
    for target in re.findall(r'\]\(([^)]+)\)', p.read_text()):
        if '://' in target or target.startswith('#'):
            continue
        target = target.split('#', 1)[0]
        assert (p.parent / target).is_file(), f'Broken link in {p.name}: {target}'
notes = root / 'docs/releases' / f'{version}.md'
assert '## English' in notes.read_text() and '## 日本語' in notes.read_text()
print(f'PASS: {version}; {len(a)} translation keys; bilingual docs, local links, MIT metadata and Gradle wrapper')

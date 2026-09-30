#!/usr/bin/env python3
"""Pin the newest published Android Codex runtime in tools/runtime-lock.json.

Reads the npm registry's "latest" dist-tag, and only moves forward. The
archive checksum comes from the registry's own sha512 integrity, which
prepare_runtime.py verifies again before packaging. Nothing is downloaded or
executed here; CI builds and checks the new runtime in the pull request.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/runtime-lock.json'
REGISTRY = 'https://registry.npmjs.org/'
# Files that name the bundled runtime version for users and licence notices.
VERSION_TEXT = ['README.md', 'README.ko.md', 'THIRD_PARTY_NOTICES.md', 'app/src/main/assets/web/index.html',
                'app/src/main/assets/web/translations.js', 'app/src/main/assets/translations-en.json']


def version_key(version):
    """0.156.1 < 0.156.1-termux.1 < 0.156.1-termux.2 < 0.157.0; other prereleases are ignored."""
    match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)(?:-termux\.(\d+))?', version)
    if not match:
        return None
    major, minor, patch, rebuild = match.groups()
    return int(major), int(minor), int(patch), int(rebuild or 0)


def newer_release(lock, metadata):
    latest = metadata.get('dist-tags', {}).get('latest', '')
    current, candidate = version_key(lock['version']), version_key(latest)
    if candidate is None or current is None or candidate <= current:
        return None
    dist = metadata['versions'][latest]['dist']
    if not str(dist.get('integrity', '')).startswith('sha512-'):
        raise ValueError(f'{latest} has no sha512 integrity; refusing to pin it')
    if not dist.get('tarball', '').startswith(REGISTRY):
        raise ValueError(f'{latest} is not served by the npm registry')
    return latest, dist


def updated_lock(lock, version, dist):
    source = re.sub(r'/tree/v[^/]+$', '/tree/v' + version, lock['source'])
    return dict(lock, version=version, url=dist['tarball'], integrity=dist['integrity'], source=source)


def replace_version_text(text, old, new):
    # Only exact runtime mentions: package@version, tag URLs and the settings footer.
    patterns = [(f'codex-cli-termux@{old}', f'codex-cli-termux@{new}'), (f'/tree/v{old}', f'/tree/v{new}'),
                (f'Codex 포트 {old}', f'Codex 포트 {new}'), (f'Codex port {old}', f'Codex port {new}')]
    for before, after in patterns:
        text = text.replace(before, after)
    return text


def fetch_metadata(package):
    request = urllib.request.Request(REGISTRY + package.replace('/', '%2f'), headers={'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--metadata', type=Path, help='registry JSON for offline use')
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    metadata = json.loads(args.metadata.read_text()) if args.metadata else fetch_metadata(lock['package'])
    release = newer_release(lock, metadata)
    outputs = {'changed': 'false', 'previous': lock['version'], 'version': lock['version']}
    if release:
        version, dist = release
        LOCK.write_text(json.dumps(updated_lock(lock, version, dist), indent=2) + '\n')
        for name in VERSION_TEXT:
            path = ROOT / name
            if path.exists():
                path.write_text(replace_version_text(path.read_text(encoding='utf-8'), lock['version'], version), encoding='utf-8')
        outputs.update(changed='true', version=version)
        print(f"Pinned {lock['package']} {lock['version']} -> {version}")
    else:
        print(f"{lock['package']} {lock['version']} is already the newest release")
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
            for key, value in outputs.items():
                out.write(f'{key}={value}\n')


if __name__ == '__main__':
    main()

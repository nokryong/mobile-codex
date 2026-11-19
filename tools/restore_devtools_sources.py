#!/usr/bin/env python3
"""Recover an identical, fully verified source bundle from a pinned public release.

Never executes or extracts downloaded code. Used only after normal collection fails.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://github.com/nokryong/mobile-codex/releases/download/v0.3.9/devtools-corresponding-source.zip'
SHA256 = '8308a21dda8dfdb8ca176137ebe29a4beefc94cbc3625b0cd081d264af772f95'
SIZE = 1163126230


def records_for(lock):
    records = [('termux-build-recipes', lock['recipeArchive'])]
    for package in lock['packages']:
        if package.get('recipeArchive'):
            records.append((package['name'] + '-build-recipes', package['recipeArchive']))
        sources = package.get('sources')
        if sources is None:
            sources = ([{'url': package['sourceArchive'], 'sha256': package['sourceSha256']}]
                       if package.get('sourceArchive') else [])
        records.extend((f"{package['name']}-{i}", source) for i, source in enumerate(sources))
    result = []
    for name, record in records:
        if not re.fullmatch(r'[0-9a-f]{64}', record['sha256']) or not record['url'].startswith('https://'):
            raise ValueError('Invalid pinned source record')
        result.append({'name': name, **record, 'archive': 'archives/' + record['sha256']})
    return result


def verify(bundle: Path, root: Path, checksum: str, size: int):
    if bundle.stat().st_size != size:
        raise ValueError('Source bundle size mismatch')
    with bundle.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != checksum:
            raise ValueError('Source bundle SHA-256 mismatch')
    fixed = {name: root / name for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md']}
    fixed['devtools-lock.json'] = root / 'tools/devtools-lock.json'
    for name in ['prepare_devtools.py', 'prepare_devtools_sources.py', 'build_native.py', 'requirements-devtools.txt']:
        fixed['tools/' + name] = root / 'tools' / name
    for path in sorted((root / 'tools/native').glob('*')):
        if path.is_file():
            fixed['tools/native/' + path.name] = path
    expected = records_for(json.loads(fixed['devtools-lock.json'].read_bytes()))
    archives = {record['archive']: record['sha256'] for record in expected}
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        wanted = set(fixed) | set(archives) | {'SOURCES.json', 'README.txt'}
        if len(names) != len(set(names)) or set(names) != wanted:
            raise ValueError('Missing, duplicate or unexpected source bundle member')
        for name, path in fixed.items():
            if archive.getinfo(name).file_size != path.stat().st_size:
                raise ValueError('Source input size mismatch: ' + name)
            if archive.read(name) != path.read_bytes():
                raise ValueError('Source input differs from current repository: ' + name)
        if archive.getinfo('SOURCES.json').file_size > 1024 * 1024:
            raise ValueError('Source manifest is too large')
        if json.loads(archive.read('SOURCES.json')) != expected:
            raise ValueError('Source manifest differs from current lock')
        for name, digest in archives.items():
            with archive.open(name) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
                    raise ValueError('Internal source SHA-256 mismatch: ' + name)


def restore(root=ROOT):
    output = root / 'artifacts/devtools-corresponding-source.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    pending = output.with_suffix('.recovery-partial')
    try:
        request = urllib.request.Request(URL, headers={'User-Agent': 'mobile-codex-source-recovery/1'})
        with urllib.request.urlopen(request, timeout=180) as source, pending.open('wb') as target:
            if not source.geturl().startswith('https://'):
                raise ValueError('Source recovery requires HTTPS')
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > SIZE:
                    raise ValueError('Source bundle exceeds pinned size')
                target.write(block)
        verify(pending, root, SHA256, SIZE)
        pending.replace(output)
        print('Recovered fully verified corresponding sources from pinned v0.3.9 asset')
        return output
    finally:
        pending.unlink(missing_ok=True)


if __name__ == '__main__':
    restore()

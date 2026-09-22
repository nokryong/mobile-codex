#!/usr/bin/env python3
"""Collect pinned upstream sources and Termux build recipes beside APK releases.

No build recipes or downloaded code are executed. Ship the resulting archive
alongside every APK containing the development tools, including CI artifacts.
"""
from __future__ import annotations
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.runtime-cache' / 'devtools-sources'


def fetch(record: dict) -> Path:
    url, checksum = record['url'], record['sha256']
    if not url.startswith('https://') or not re.fullmatch(r'[0-9a-f]{64}', checksum):
        raise ValueError('Source URL/checksum is not pinned: ' + url)
    if any(c in url for c in '$(){}'):
        raise ValueError('Unresolved source recipe: ' + url)
    path = CACHE / checksum
    if not path.exists():
        pending = path.with_suffix('.partial')
        request = urllib.request.Request(url, headers={'User-Agent': 'mobile-codex-source-bundle/1'})
        with urllib.request.urlopen(request, timeout=180) as source, pending.open('wb') as out:
            while block := source.read(1024 * 1024):
                out.write(block)
        pending.replace(path)
    with path.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != checksum:
            path.unlink()
            raise ValueError('Source checksum mismatch: ' + url)
    return path


def prepare() -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / 'tools/devtools-lock.json').read_text(encoding='utf-8'))
    recipes = lock['recipeArchive']
    records = [("termux-build-recipes", recipes)]
    for package in lock['packages']:
        # A rolling package pool may require updating one binary while keeping
        # the remaining inputs pinned. Include that package's matching recipes
        # as well as the original snapshot, including local patches/build files.
        if package.get('recipeArchive'):
            records.append((package['name'] + '-build-recipes', package['recipeArchive']))
        sources = package.get('sources')
        if sources is None:
            sources = ([{'url': package['sourceArchive'], 'sha256': package['sourceSha256']}]
                       if package.get('sourceArchive') else [])
        for index, source in enumerate(sources):
            records.append((f"{package['name']}-{index}", source))
    # De-duplicate archives shared by split packages.
    unique = {record['sha256']: record for _, record in records}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        paths = dict(zip(unique, pool.map(fetch, unique.values())))
    output = ROOT / 'artifacts/devtools-corresponding-source.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    pending = output.with_suffix('.partial')
    with zipfile.ZipFile(pending, 'w', compression=zipfile.ZIP_STORED) as archive:
        archive.write(ROOT / 'tools/devtools-lock.json', 'devtools-lock.json')
        archive.writestr('SOURCES.json', json.dumps([{'name': name, **record, 'archive': 'archives/' + record['sha256']}
                                                   for name, record in records], indent=2) + '\n')
        archive.writestr('README.txt', 'Corresponding upstream sources and pinned Termux recipes for Mobile Codex development tools.\n'
                         'SOURCES.json maps each package to its archive (identified by SHA-256), original URL and checksum.\n'
                         'Extract archives with tar/zip according to their content. The Termux recipe archive includes\n'
                         'patches, local package sources, build scripts and the dependency recipes used by the port.\n'
                         'Mobile Codex packaging patches and launcher source are in tools/. No package scripts were run.\n'
                         'Individual source archives contain their own copyright and license notices.\n')
        for checksum, path in paths.items():
            archive.write(path, 'archives/' + checksum)
        for name in ['prepare_devtools.py', 'prepare_devtools_sources.py', 'build_native.py', 'requirements-devtools.txt']:
            archive.write(ROOT / 'tools' / name, 'tools/' + name)
        for path in sorted((ROOT / 'tools/native').glob('*')):
            if path.is_file(): archive.write(path, 'tools/native/' + path.name)
    pending.replace(output)
    print(f'Prepared corresponding sources: {output.name} ({output.stat().st_size} bytes)')
    return output


if __name__ == '__main__':
    prepare()

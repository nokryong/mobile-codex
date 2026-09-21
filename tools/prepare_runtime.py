#!/usr/bin/env python3
"""Bundle a pinned Android Codex ELF without running npm lifecycle scripts.

The binary is a third-party Android port of openai/codex, not an official
OpenAI Android build. No runtime code is downloaded on the user's device.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def verify(path, integrity):
    algorithm, expected = integrity.split('-', 1)
    if algorithm != 'sha512':
        raise ValueError('Only SHA-512 pinned archives are accepted')
    h = hashlib.sha512()
    with path.open('rb') as f:
        for data in iter(lambda: f.read(1024 * 1024), b''):
            h.update(data)
    if h.digest() != base64.b64decode(expected, validate=True):
        raise ValueError('Runtime checksum mismatch; refusing to package')


def patch_host_name(binary):
    # Android's package manager extracts executable native files named lib*.so.
    # The pinned Rust binary resolves this sibling by a fixed-width string.
    # Keep byte length identical: ELF offsets/relocations remain unchanged.
    old, new = b'codex-code-mode-host', b'libcodexmodehostx.so'
    if len(old) != len(new) or binary.count(old) != 2:
        raise ValueError('Unknown Codex host layout; review the new runtime before packaging')
    return binary.replace(old, new)


def prepare(archive):
    lock = json.loads((ROOT / 'tools/runtime-lock.json').read_text())
    if archive is None:
        archive = ROOT / '.runtime-cache' / f"codex-{lock['version']}.tgz"
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.exists():
            partial = archive.with_suffix('.partial')
            with urllib.request.urlopen(lock['url'], timeout=180) as src, partial.open('wb') as dst:
                shutil.copyfileobj(src, dst)
            verify(partial, lock['integrity'])
            partial.replace(archive)
    verify(archive, lock['integrity'])
    out = ROOT / 'app/src/main/jniLibs/arm64-v8a'
    assets = ROOT / 'app/src/main/assets/runtime'
    out.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    # Do not extract arbitrary archive paths or execute package scripts.
    mappings = {
        'package/bin/codex.bin': out / 'libcodex.so',
        'package/bin/libc++_shared.so': out / 'libc++_shared.so',
        'package/bin/codex-code-mode-host': out / 'libcodexmodehostx.so',
        'package/LICENSE': assets / 'CODEX-LICENSE.txt',
        'package/NOTICE': assets / 'CODEX-NOTICE.txt',
    }
    obsolete = out / 'libcodex_code_mode_host.so'
    if obsolete.exists():
        obsolete.unlink()
    manifest = dict(lock, files={}, packagingPatch='Fixed-width host name: codex-code-mode-host -> libcodexmodehostx.so')
    with tarfile.open(archive, 'r:gz') as tar:
        for source, target in mappings.items():
            member = tar.getmember(source)
            if not member.isfile():
                raise ValueError(f'Unexpected archive member: {source}')
            with tar.extractfile(member) as src, target.open('wb') as dst:
                shutil.copyfileobj(src, dst)
            if target.name == 'libcodex.so':
                target.write_bytes(patch_host_name(target.read_bytes()))
            if target.suffix == '.so':
                with target.open('rb') as f:
                    header = f.read(20)
                if header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != 183:
                    raise ValueError(f'Not an ARM64 ELF: {source}')
                target.chmod(0o755)
                manifest['files'][target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f"Prepared Codex Android {lock['version']} for {lock['abi']}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path)
    prepare(parser.parse_args().archive)

#!/usr/bin/env python3
"""Mirror the canonical public Mobile Codex release into the paired-repo build.

This is deliberately a fail-closed CI gate.  It is only invoked for the
SeeUSoon93 main/full-release workflow, and does not create, edit, or upload a
GitHub release.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

from prepare_update_release import metadata as apk_metadata
from sign_ci_apk import EXPECTED_CERT_SHA256, verify_certificate

CANONICAL_REPO = 'nokryong/mobile-codex'
MIRROR_REPO = 'SeeUSoon93/mobile-codex'
POLL_SECONDS = 15
TIMEOUT_SECONDS = 10 * 60
SOURCE_ZIP = 'devtools-corresponding-source.zip'
LEGAL_ASSETS = ('LICENSE', 'THIRD_PARTY_NOTICES.md')


def gh(*args):
    """Use the workflow's existing gh authentication; never read or print it."""
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True).stdout


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def release_names(version: str) -> tuple[str, ...]:
    apk = f'mobile-codex-{version}-arm64.apk'
    return (apk, apk + '.sha256', 'mobile-codex-update.json', SOURCE_ZIP, *LEGAL_ASSETS)


def api(path: str) -> dict:
    return json.loads(gh('api', path))


def canonical_release(tag: str) -> dict | None:
    try:
        release = api(f'repos/{CANONICAL_REPO}/releases/tags/{tag}')
    except subprocess.CalledProcessError as error:
        # A missing public release is the only retryable API error.  Do not
        # convert authentication, rate-limit, or network failures into a wait.
        detail = (error.stderr or '') + (error.stdout or '')
        if error.returncode == 1 and re.search(r'\bHTTP\s+404\b', detail, re.IGNORECASE):
            return None
        raise
    if release.get('draft'):
        return None
    return release


def wait_for_release(tag: str, timeout: int = TIMEOUT_SECONDS, interval: int = POLL_SECONDS, clock=time.monotonic, sleep=time.sleep) -> dict:
    deadline = clock() + timeout
    while True:
        release = canonical_release(tag)
        if release is not None:
            return release
        if clock() >= deadline:
            raise TimeoutError(f'Canonical public release {tag} was not available before the mirror timeout.')
        sleep(interval)


def canonical_tag_tree(tag: str) -> str:
    ref = api(f'repos/{CANONICAL_REPO}/git/ref/tags/{tag}')
    target = ref.get('object', {})
    if target.get('type') == 'tag':
        target = api(f'repos/{CANONICAL_REPO}/git/tags/{target.get("sha", "")}').get('object', {})
    if target.get('type') != 'commit' or not isinstance(target.get('sha'), str):
        raise ValueError('Canonical release tag does not resolve to a commit.')
    commit = api(f'repos/{CANONICAL_REPO}/git/commits/{target["sha"]}')
    tree = commit.get('tree', {}).get('sha')
    if not isinstance(tree, str) or len(tree) != 40:
        raise ValueError('Canonical release tag has no valid commit tree.')
    return tree


def local_tree() -> str:
    return subprocess.run(['git', 'rev-parse', 'HEAD^{tree}'], check=True, capture_output=True, text=True).stdout.strip()


def validate_release_assets(release: dict, names: tuple[str, ...]) -> dict[str, dict]:
    assets = release.get('assets')
    if not isinstance(assets, list):
        raise ValueError('Canonical release has no asset list.')
    by_name = {}
    for asset in assets:
        name = asset.get('name') if isinstance(asset, dict) else None
        if not isinstance(name, str) or name in by_name:
            raise ValueError('Canonical release asset names are invalid.')
        by_name[name] = asset
    if set(by_name) != set(names):
        raise ValueError('Canonical release assets do not exactly match the required six files.')
    for name in names:
        asset = by_name[name]
        digest = asset.get('digest')
        if not isinstance(asset.get('size'), int) or asset['size'] <= 0 or not isinstance(digest, str) or not digest.startswith('sha256:') or len(digest) != 71:
            raise ValueError('Canonical release asset is missing a size or SHA-256 digest: ' + name)
    return by_name


def download_assets(tag: str, destination: Path, names: tuple[str, ...]):
    destination.mkdir(parents=True, exist_ok=True)
    for name in names:
        gh('release', 'download', tag, '--repo', CANONICAL_REPO, '--dir', str(destination), '--pattern', name)


def validate_downloads(folder: Path, assets: dict[str, dict], names: tuple[str, ...]):
    for name in names:
        path = folder / name
        if not path.is_file() or path.stat().st_size != assets[name]['size']:
            raise ValueError('Canonical download has an unexpected size: ' + name)
        if assets[name]['digest'] != 'sha256:' + sha256(path):
            raise ValueError('Canonical download digest mismatch: ' + name)


def inspect_apk(apk: Path, build_tools: Path) -> dict:
    aapt = subprocess.run([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    signing = subprocess.run([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], check=True, capture_output=True, text=True).stdout
    verify_certificate(signing)
    return apk_metadata(aapt, signing, apk)


def validate_identity(stage: Path, version: str, local: dict, build_tools: Path):
    expected_apk = f'mobile-codex-{version}-arm64.apk'
    remote = json.loads((stage / 'mobile-codex-update.json').read_text(encoding='utf-8'))
    inspected = inspect_apk(stage / expected_apk, build_tools)
    # The downloaded canonical APK must match all of its own metadata.  The
    # mirror's locally built APK can legitimately have a different digest and
    # size because BuildConfig.SOURCE_SHA contains its merge commit; only its
    # install identity must match before we replace it with canonical bytes.
    for key in ('versionName', 'versionCode', 'applicationId', 'signingCertificateSha256', 'fileName', 'sha256', 'size'):
        if remote.get(key) != inspected.get(key):
            raise ValueError('Canonical APK identity mismatch: ' + key)
    for key in ('versionName', 'versionCode', 'applicationId', 'signingCertificateSha256', 'fileName'):
        if local.get(key) != remote.get(key):
            raise ValueError('Mirror APK identity mismatch: ' + key)
    if remote.get('signingCertificateSha256') != [EXPECTED_CERT_SHA256]:
        raise ValueError('Canonical APK has an unexpected signing certificate.')
    checksum = (stage / (expected_apk + '.sha256')).read_text(encoding='utf-8').strip()
    if checksum != remote['sha256'] + '  ' + expected_apk:
        raise ValueError('Canonical APK checksum file is invalid.')
    for legal in LEGAL_ASSETS:
        if (stage / legal).read_bytes() != Path(legal).read_bytes():
            raise ValueError('Canonical public ' + legal + ' differs from this source tree.')


def replace_verified_assets(stage: Path, version: str):
    apk = f'mobile-codex-{version}-arm64.apk'
    targets = [
        (stage / apk, Path('app/build/outputs/apk/debug/app-debug.apk')),
        (stage / apk, Path('artifacts/update') / apk),
        (stage / (apk + '.sha256'), Path('artifacts/update') / (apk + '.sha256')),
        (stage / 'mobile-codex-update.json', Path('artifacts/update/mobile-codex-update.json')),
        (stage / SOURCE_ZIP, Path('artifacts') / SOURCE_ZIP),
    ]
    # Stage every replacement beside its destination first.  A failed
    # validation above therefore leaves generated files untouched.
    pending = []
    try:
        for source, target in targets:
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_name('.canonical-' + target.name)
            shutil.copyfile(source, temporary)
            pending.append((temporary, target))
        for temporary, target in pending:
            temporary.replace(target)
    finally:
        for temporary, _ in pending:
            temporary.unlink(missing_ok=True)


def sync(build_tools: Path, timeout: int = TIMEOUT_SECONDS, interval: int = POLL_SECONDS):
    local = json.loads(Path('artifacts/update/mobile-codex-update.json').read_text(encoding='utf-8'))
    version = local.get('versionName')
    if not isinstance(version, str):
        raise ValueError('Local update metadata has no versionName.')
    tag = 'v' + version
    release = wait_for_release(tag, timeout, interval)
    if canonical_tag_tree(tag) != local_tree():
        raise ValueError('Canonical release tag tree differs from this mirror checkout.')
    names = release_names(version)
    assets = validate_release_assets(release, names)
    with tempfile.TemporaryDirectory(prefix='mobile-codex-canonical-') as directory:
        stage = Path(directory)
        download_assets(tag, stage, names)
        validate_downloads(stage, assets, names)
        validate_identity(stage, version, local, build_tools)
        replace_verified_assets(stage, version)
    # The mirror publisher uses these exact public notes while retaining its
    # own merge commit as the tag target.
    Path('artifacts/canonical-release-notes.md').write_text(release.get('body', ''), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--timeout', type=int, default=TIMEOUT_SECONDS)
    parser.add_argument('--interval', type=int, default=POLL_SECONDS)
    args = parser.parse_args()
    if os.environ.get('GITHUB_REPOSITORY') != MIRROR_REPO or os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' or os.environ.get('INPUTS_APK_ONLY', '').lower() == 'true':
        return
    sync(args.build_tools, args.timeout, args.interval)


if __name__ == '__main__':
    main()

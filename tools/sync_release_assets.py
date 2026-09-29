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
FAILED_WORKFLOW_CONCLUSIONS = frozenset({
    'failure', 'cancelled', 'timed_out', 'action_required', 'startup_failure',
})
WORKFLOW_TREE_CACHE: dict[str, str] = {}


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


def canonical_commit_tree(commit: str) -> str:
    """Return a canonical commit's tree, rejecting malformed API responses."""
    data = api(f'repos/{CANONICAL_REPO}/git/commits/{commit}')
    tree = data.get('tree', {}).get('sha')
    if not isinstance(tree, str) or not re.fullmatch(r'[0-9a-f]{40}', tree):
        raise ValueError('Canonical workflow commit has no valid tree.')
    return tree


def failed_canonical_workflow(expected_tree: str) -> dict | None:
    """Find the newest canonical Android run whose source tree is expected_tree.

    A mirror checkout can have a different merge commit from the public
    checkout, so workflow ``head_sha`` alone is not sufficient.  Looking up
    the commit tree prevents an unrelated (or merely older) main run from
    ending this bounded wait early.
    """
    # Restrict this to the canonical Android release workflow.  A failed
    # documentation or housekeeping workflow must never block publication.
    # Do not filter by event: a workflow_dispatch rerun for the same source
    # tree is the newest authoritative outcome after a failed push run.
    data = api(f'repos/{CANONICAL_REPO}/actions/workflows/android.yml/runs?branch=main&per_page=30')
    runs = data.get('workflow_runs')
    if not isinstance(runs, list):
        raise ValueError('Canonical workflow runs response is invalid.')
    matching = []
    for run in runs:
        if not isinstance(run, dict) or run.get('head_branch') != 'main':
            continue
        head_sha = run.get('head_sha')
        if not isinstance(head_sha, str) or not re.fullmatch(r'[0-9a-f]{40}', head_sha):
            continue
        tree = WORKFLOW_TREE_CACHE.get(head_sha)
        if tree is None:
            tree = canonical_commit_tree(head_sha)
            WORKFLOW_TREE_CACHE[head_sha] = tree
        if tree == expected_tree:
            matching.append(run)
    if not matching:
        return None
    # API results are normally newest first, but make that assumption explicit
    # before deciding whether a prior failed retry is still relevant.
    newest = max(matching, key=lambda run: (str(run.get('created_at', '')), int(run.get('id', 0))))
    if newest.get('status') == 'completed' and newest.get('conclusion') in FAILED_WORKFLOW_CONCLUSIONS:
        return newest
    return None


def wait_for_release(tag: str, timeout: int = TIMEOUT_SECONDS, interval: int = POLL_SECONDS,
                     clock=time.monotonic, sleep=time.sleep, expected_tree: str | None = None,
                     failure_check=failed_canonical_workflow) -> dict:
    deadline = clock() + timeout
    while True:
        release = canonical_release(tag)
        if release is not None:
            return release
        if expected_tree is not None:
            failed = failure_check(expected_tree)
            if failed is not None:
                conclusion = failed.get('conclusion', 'failed')
                url = failed.get('html_url')
                detail = f' ({url})' if isinstance(url, str) else ''
                raise RuntimeError(f'Canonical main workflow for the matching source tree {conclusion}{detail}.')
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


def checked_out_identity(build_gradle: Path = Path('app/build.gradle')) -> dict:
    """Read the literal release identity from the checked-out Gradle file.

    This deliberately supports only the small, literal declarations used by
    this project.  Expressions, duplicate declarations and unexpected values
    fail closed instead of guessing what Gradle might evaluate to.
    """
    content = build_gradle.read_text(encoding='utf-8')

    def literal(pattern: str, field: str) -> str:
        values = re.findall(pattern, content, re.MULTILINE)
        if len(values) != 1:
            raise ValueError(f'Could not read exactly one literal {field} from app/build.gradle.')
        return values[0]

    application_id = literal(r"^\s*applicationId\s+'([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)'\s*(?://[^\n]*)?$", 'applicationId')
    version_code = literal(r'^\s*versionCode\s+([1-9][0-9]*)\s*(?://[^\n]*)?$', 'versionCode')
    version_name = literal(r"^\s*versionName\s+'(\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)(?:[.-]?\d+)?)?)'\s*(?://[^\n]*)?$", 'versionName')
    if application_id != 'dev.mobilecodex.app':
        raise ValueError('Unexpected literal applicationId in app/build.gradle.')
    return {
        'applicationId': application_id,
        'versionCode': int(version_code),
        'versionName': version_name,
        'fileName': f'mobile-codex-{version_name}-arm64.apk',
        'signingCertificateSha256': [EXPECTED_CERT_SHA256],
    }


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


def validate_identity(stage: Path, version: str, expected: dict, build_tools: Path):
    expected_apk = f'mobile-codex-{version}-arm64.apk'
    remote = json.loads((stage / 'mobile-codex-update.json').read_text(encoding='utf-8'))
    inspected = inspect_apk(stage / expected_apk, build_tools)
    # The downloaded canonical APK must match all of its own metadata.
    for key in ('versionName', 'versionCode', 'applicationId', 'signingCertificateSha256', 'fileName', 'sha256', 'size'):
        if remote.get(key) != inspected.get(key):
            raise ValueError('Canonical APK identity mismatch: ' + key)
    for key in ('versionName', 'versionCode', 'applicationId', 'signingCertificateSha256', 'fileName'):
        if expected.get(key) != remote.get(key):
            raise ValueError('Mirror source identity mismatch: ' + key)
    if remote.get('signingCertificateSha256') != [EXPECTED_CERT_SHA256]:
        raise ValueError('Canonical APK has an unexpected signing certificate.')
    checksum = (stage / (expected_apk + '.sha256')).read_text(encoding='utf-8').strip()
    if checksum != remote['sha256'] + '  ' + expected_apk:
        raise ValueError('Canonical APK checksum file is invalid.')
    for legal in LEGAL_ASSETS:
        if (stage / legal).read_bytes() != Path(legal).read_bytes():
            raise ValueError('Canonical public ' + legal + ' differs from this source tree.')


def replace_verified_assets(stage: Path, version: str, include_local_apk: bool = True):
    apk = f'mobile-codex-{version}-arm64.apk'
    targets = [
        (stage / apk, Path('artifacts/update') / apk),
        (stage / (apk + '.sha256'), Path('artifacts/update') / (apk + '.sha256')),
        (stage / 'mobile-codex-update.json', Path('artifacts/update/mobile-codex-update.json')),
        (stage / SOURCE_ZIP, Path('artifacts') / SOURCE_ZIP),
    ]
    if include_local_apk:
        targets.insert(0, (stage / apk, Path('app/build/outputs/apk/debug/app-debug.apk')))
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
    tree = local_tree()
    release = wait_for_release(tag, timeout, interval, expected_tree=tree)
    if canonical_tag_tree(tag) != tree:
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


def sync_without_build(build_tools: Path, timeout: int = TIMEOUT_SECONDS, interval: int = POLL_SECONDS):
    """Mirror canonical assets without a local Gradle/runtime/signing build."""
    expected = checked_out_identity()
    version = expected['versionName']
    tag = 'v' + version
    tree = local_tree()
    release = wait_for_release(tag, timeout, interval, expected_tree=tree)
    if canonical_tag_tree(tag) != tree:
        raise ValueError('Canonical release tag tree differs from this mirror checkout.')
    names = release_names(version)
    assets = validate_release_assets(release, names)
    with tempfile.TemporaryDirectory(prefix='mobile-codex-canonical-') as directory:
        stage = Path(directory)
        download_assets(tag, stage, names)
        validate_downloads(stage, assets, names)
        validate_identity(stage, version, expected, build_tools)
        # This path intentionally neither reads nor creates a local APK.  The
        # publisher consumes artifacts/update, and its source ZIP is canonical.
        replace_verified_assets(stage, version, include_local_apk=False)
    Path('artifacts/canonical-release-notes.md').write_text(release.get('body', ''), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--timeout', type=int, default=TIMEOUT_SECONDS)
    parser.add_argument('--interval', type=int, default=POLL_SECONDS)
    parser.add_argument('--without-build', action='store_true',
                        help='verify and mirror canonical assets without a local APK build')
    args = parser.parse_args()
    if os.environ.get('GITHUB_REPOSITORY') != MIRROR_REPO or os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' or os.environ.get('INPUTS_APK_ONLY', '').lower() == 'true':
        return
    if args.without_build:
        sync_without_build(args.build_tools, args.timeout, args.interval)
    else:
        sync(args.build_tools, args.timeout, args.interval)


if __name__ == '__main__':
    main()

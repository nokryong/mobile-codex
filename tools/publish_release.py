#!/usr/bin/env python3
"""Publish verified build assets as a versioned GitHub release using the Actions token."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def gh(*args):
    result = subprocess.run(['gh', *args], capture_output=True, text=True)
    if result.returncode != 0:
        # Surface gh's own message; a bare CalledProcessError hides why a release failed.
        raise RuntimeError(f"gh {' '.join(args[:3])} failed ({result.returncode}): {(result.stderr or result.stdout).strip()}")
    return result.stdout


def validate_assets(folder: Path, *, apk_path=None, build_tools=None) -> tuple[dict, list[Path]]:
    metadata = json.loads((folder / 'mobile-codex-update.json').read_text())
    version = metadata['versionName']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)(?:[.-]?\d+)?)?', version):
        raise ValueError('Invalid release version')
    expected = f'mobile-codex-{version}-arm64.apk'
    if metadata['applicationId'] != 'dev.mobilecodex.app' or metadata['fileName'] != expected:
        raise ValueError('Unexpected APK identity')
    apk = folder / expected
    if apk_path is not None and apk_path.resolve() != apk.resolve():
        raise ValueError('Explicit APK path does not match the update manifest')
    policy = json.loads(Path(__file__).with_name('release-policy.json').read_text())
    if (metadata.get('debuggable') is not False or metadata.get('testOnly') is not False
            or metadata.get('signingCertificateSha256') != policy['signingCertificateSha256']
            or metadata.get('abi') != policy['abi'] or metadata.get('minimumSdk') != policy['minimumSdk']
            or type(metadata.get('versionCode')) is not int or metadata['versionCode'] <= policy['minimumPreviousVersionCode']):
        raise ValueError('Update manifest is not a compatible production APK')
    if build_tools is not None:
        from prepare_update_release import inspect_apk
        if inspect_apk(apk, build_tools, expected=metadata) != metadata:
            raise ValueError('Final APK does not match its verified manifest')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != metadata['sha256'] or apk.stat().st_size != metadata['size']:
        raise ValueError('APK does not match verified metadata')
    checksum = folder / (expected + '.sha256')
    if checksum.read_text().strip() != digest + '  ' + expected:
        raise ValueError('Checksum file does not match APK')
    assets = [apk, checksum, folder / 'mobile-codex-update.json',
              Path('artifacts/devtools-corresponding-source.zip'), Path('LICENSE'), Path('THIRD_PARTY_NOTICES.md')]
    if not all(p.is_file() and p.stat().st_size > 0 for p in assets):
        raise ValueError('Missing release assets or corresponding sources')
    return metadata, assets


def publish(*, folder=Path('artifacts/update'), apk_path=None, build_tools=None, previous_manifest=None):
    repo, commit = os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA']
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') not in ('push', 'workflow_dispatch'):
        raise ValueError('Releases can only be published from main push/manual builds')
    metadata, assets = validate_assets(folder, apk_path=apk_path, build_tools=build_tools)
    if previous_manifest is not None:
        previous = json.loads(previous_manifest.read_text())
        if previous.get('applicationId') != metadata['applicationId'] or metadata['versionCode'] <= previous['versionCode']:
            raise ValueError('versionCode must increase beyond the published version')
    version = metadata['versionName']; tag = 'v' + version
    # A successful full list request distinguishes an absent tag from auth/network failures.
    releases = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    existing = next((r for page in releases for r in page if r['tag_name'] == tag), None)
    if existing and not existing['draft']:
        print(f'::notice::Release {tag} already exists; published assets are immutable. Bump versionName and versionCode for the next release.')
        return
    if not existing:
        notes = (f'## Install\nDownload **{metadata["fileName"]}** below and open it on an ARM64 device running Android 10 or later.\n\n'
                 'This is an independent client, not an official OpenAI app. Review the README for setup and limitations.\n\n'
                 'The APK uses the existing release signing key. Your installed app must have a compatible package and signing certificate.\n\n'
                 '## Source and verification\nThe source archives for this tag contain the app source. '
                 '`devtools-corresponding-source.zip` contains pinned development-tool sources, patches, and build recipes. '
                 'The JSON manifest and SHA-256 file describe the verified APK. Third-party licenses are retained.\n\n'
                 f'Build commit: `{commit}`\n')
        changes = Path('docs/releases') / (version + '.md')
        if changes.is_file():
            notes = changes.read_text(encoding='utf-8').rstrip() + '\n\n' + notes
        with tempfile.TemporaryDirectory() as directory:
            body = Path(directory) / 'release.md'; body.write_text(notes, encoding='utf-8')
            args = ['release','create',tag,'--repo',repo,'--target',commit,'--title',f'Mobile Codex {version}','--notes-file',str(body),'--draft']
            if '-' in version: args.append('--prerelease')
            gh(*args)
    else:
        if existing.get('target_commitish') != commit:
            raise ValueError('Existing draft belongs to another commit; refusing to replace its assets')
    # A rerun can repair an incomplete draft. Public assets are never overwritten.
    gh('release','upload',tag,*map(str,assets),'--repo',repo,'--clobber')
    remote = json.loads(gh('release','view',tag,'--repo',repo,'--json','assets'))['assets']
    for path in assets:
        if not any(a['name'] == path.name and a['size'] == path.stat().st_size for a in remote):
            raise ValueError('Release upload incomplete: ' + path.name)
    gh('release','edit',tag,'--repo',repo,'--draft=false')
    print(f'Published https://github.com/{repo}/releases/tag/{tag}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--previous-manifest', type=Path, required=True)
    args = parser.parse_args()
    publish(folder=args.folder, apk_path=args.apk, build_tools=args.build_tools, previous_manifest=args.previous_manifest)

#!/usr/bin/env python3
"""Publish verified build assets as a versioned GitHub release using the Actions token."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def gh(*args):
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True).stdout


def validate_assets(folder: Path) -> tuple[dict, list[Path]]:
    metadata = json.loads((folder / 'mobile-codex-update.json').read_text())
    version = metadata['versionName']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)(?:[.-]?\d+)?)?', version):
        raise ValueError('Invalid release version')
    expected = f'mobile-codex-{version}-arm64.apk'
    if metadata['applicationId'] != 'dev.mobilecodex.app' or metadata['fileName'] != expected:
        raise ValueError('Unexpected APK identity')
    apk = folder / expected
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


def publish():
    repo, commit = os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA']
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') not in ('push', 'workflow_dispatch'):
        raise ValueError('Releases can only be published from main push/manual builds')
    metadata, assets = validate_assets(Path('artifacts/update'))
    version = metadata['versionName']; tag = 'v' + version
    # A successful full list request distinguishes an absent tag from auth/network failures.
    releases = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    existing = next((r for page in releases for r in page if r['tag_name'] == tag), None)
    if existing and not existing['draft']:
        print(f'::notice::Release {tag} already exists; published assets are immutable. Bump versionName and versionCode for the next release.')
        return
    if not existing:
        notes = (f'## Install\nDownload **{metadata["fileName"]}** below and open it on an ARM64 device running Android 10 or later.\n\n'
                 'This is an independent alpha client, not an official OpenAI app. Review the README for setup and limitations.\n\n'
                 'The APK uses the existing release signing key. Your installed app must have a compatible package and signing certificate.\n\n'
                 '## Source and verification\nThe source archives for this tag contain the app source. '
                 '`devtools-corresponding-source.zip` contains pinned development-tool sources, patches, and build recipes. '
                 'The JSON manifest and SHA-256 file describe the verified APK. Third-party licenses are retained.\n\n'
                 f'Build commit: `{commit}`\n')
        changes = Path('docs/releases') / (version + '.md')
        if changes.is_file():
            notes = changes.read_text(encoding='utf-8').rstrip() + '\n\n' + notes
        with tempfile.TemporaryDirectory() as directory:
            body = Path(directory) / 'release.md'; body.write_text(notes)
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
    publish()

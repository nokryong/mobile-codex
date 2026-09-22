#!/usr/bin/env python3
"""Prepare reviewable release assets from an APK; never uploads or creates signing keys."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


def metadata(badging: str, signing: str, apk: Path) -> dict:
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    # aapt2 renamed sdkVersion to minSdkVersion; accept both tool generations.
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", signing)
    if not package or not minimum or not certificates:
        missing = [name for name, value in [('package identity', package), ('minimum SDK', minimum),
                                            ('signing certificate', certificates)] if not value]
        raise ValueError('Could not verify APK metadata: ' + ', '.join(missing))
    app_id, code, version = package.groups()
    if app_id != 'dev.mobilecodex.app' or not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)(?:[.-]?\d+)?)?', version):
        raise ValueError('Unexpected application id or version')
    if "native-code: 'arm64-v8a'" not in badging:
        raise ValueError('Expected ARM64 APK')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return {'applicationId': app_id, 'versionCode': int(code), 'versionName': version,
            'minimumSdk': int(minimum.group(1)), 'abi': 'arm64-v8a',
            'fileName': f'mobile-codex-{version}-arm64.apk', 'size': apk.stat().st_size,
            'sha256': digest.hexdigest(), 'signingCertificateSha256': sorted(set(s.lower() for s in certificates))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=Path('app/build/outputs/apk/debug/app-debug.apk'))
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('artifacts/update'))
    args = parser.parse_args()
    badging = subprocess.run([str(args.build_tools / 'aapt2'), 'dump', 'badging', str(args.apk)], check=True, capture_output=True, text=True).stdout
    signing = subprocess.run([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(args.apk)], check=True, capture_output=True, text=True).stdout
    result = metadata(badging, signing, args.apk)
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, args.output / result['fileName'])
    (args.output / 'mobile-codex-update.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    (args.output / (result['fileName'] + '.sha256')).write_text(result['sha256'] + '  ' + result['fileName'] + '\n', encoding='utf-8')
    print('Prepared update assets:', result['fileName'])


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Prepare reviewable release assets from an APK; never uploads or creates signing keys."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


POLICY = json.loads(Path(__file__).with_name('release-policy.json').read_text())


def metadata(badging: str, signing: str, apk: Path, manifest: str, *, previous_version_code=67, expected=None) -> dict:
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    # aapt2 renamed sdkVersion to minSdkVersion; accept both tool generations.
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", signing)
    if not package or not minimum or not certificates:
        missing = [name for name, value in [('package identity', package), ('minimum SDK', minimum),
                                            ('signing certificate', certificates)] if not value]
        raise ValueError('Could not verify APK metadata: ' + ', '.join(missing))
    app_id, code, version = package.groups()
    if app_id != POLICY['applicationId'] or not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)(?:[.-]?\d+)?)?', version):
        raise ValueError('Unexpected application id or version')
    native = re.findall(r'^native-code:\s*(.*)$', badging, re.MULTILINE)
    if native != ["'arm64-v8a'"]:
        raise ValueError('Expected ARM64 APK')
    if int(minimum.group(1)) != POLICY['minimumSdk']:
        raise ValueError('Unexpected minimum SDK')
    if [s.lower() for s in certificates] != POLICY['signingCertificateSha256']:
        raise ValueError('Unexpected signing certificate')
    if int(code) <= max(POLICY['minimumPreviousVersionCode'], previous_version_code):
        raise ValueError('versionCode must increase beyond the published version')
    if expected and (int(code) != expected['versionCode'] or version != expected['versionName']):
        raise ValueError('APK version does not match the checked-out build identity')
    if 'E: manifest' not in manifest or 'E: application' not in manifest:
        raise ValueError('Could not verify Android manifest flags')
    for name in ('debuggable', 'testOnly'):
        attributes = re.findall(r'A: android:' + name + r'\([^\n]*', manifest)
        if any(not re.fullmatch(r'A: android:' + name + r'\(0x[0-9a-fA-F]+\)=\(type 0x12\)0x0\s*', value) for value in attributes):
            raise ValueError('Production APK must have ' + name + '=false')
    if 'application-debuggable' in badging or 'application-testOnly' in badging:
        raise ValueError('Production APK cannot be debuggable or testOnly')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return {'applicationId': app_id, 'versionCode': int(code), 'versionName': version,
            'minimumSdk': int(minimum.group(1)), 'abi': 'arm64-v8a',
            'fileName': f'mobile-codex-{version}-arm64.apk', 'size': apk.stat().st_size,
            'sha256': digest.hexdigest(), 'signingCertificateSha256': sorted(set(s.lower() for s in certificates)),
            'debuggable': False, 'testOnly': False}


def inspect_apk(apk, build_tools, **kwargs):
    aapt = str(build_tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2'))
    signer = str(build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner'))
    def run(argv):
        return subprocess.run(argv, check=True, capture_output=True, text=True).stdout
    return metadata(run([aapt, 'dump', 'badging', str(apk)]),
                    run([signer, 'verify', '--print-certs', str(apk)]), apk,
                    run([aapt, 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml']), **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('artifacts/update'))
    parser.add_argument('--previous-manifest', type=Path, required=True)
    parser.add_argument('--identity', type=Path, required=True)
    args = parser.parse_args()
    previous = json.loads(args.previous_manifest.read_text())
    if previous.get('applicationId') != POLICY['applicationId'] or type(previous.get('versionCode')) is not int:
        raise ValueError('Invalid previous release identity')
    from sync_release_assets import checked_out_identity
    result = inspect_apk(args.apk, args.build_tools, previous_version_code=previous['versionCode'], expected=checked_out_identity(args.identity))
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, args.output / result['fileName'])
    (args.output / 'mobile-codex-update.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    (args.output / (result['fileName'] + '.sha256')).write_text(result['sha256'] + '  ' + result['fileName'] + '\n', encoding='utf-8')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
            out.write('apk-path=' + str(args.output / result['fileName']) + '\n')
    print('Prepared update assets:', result['fileName'])


if __name__ == '__main__':
    main()

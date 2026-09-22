#!/usr/bin/env python3
"""Sign CI APKs with the existing installation key, never a generated fallback."""
import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

EXPECTED_CERT_SHA256 = 'f9a8d59abf5ab33b44879ade1b9500c385b2cf03e1cb17f94936eadb85337bd7'
SECRET_NAME = 'MOBILE_CODEX_SIGNING_JSON'


class SigningError(RuntimeError):
    pass


def read_credentials(raw):
    if not raw:
        raise SigningError('Configure the MOBILE_CODEX_SIGNING_JSON repository secret before building an update APK.')
    try:
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise ValueError()
        for field in ('keystoreBase64', 'keyAlias', 'storePassword', 'keyPassword'):
            if not isinstance(data.get(field), str) or not data[field] or '\0' in data[field]:
                raise ValueError()
        key = base64.b64decode(data['keystoreBase64'], validate=True)
        if not key or len(key) > 65536:
            raise ValueError()
    except (ValueError, TypeError, binascii.Error):
        raise SigningError('Invalid signing secret: expected keystoreBase64, keyAlias, storePassword and keyPassword.') from None
    return data, key


def checked_run(args, env):
    # Capture diagnostics: a tool error must never echo supplied credentials.
    try:
        return subprocess.run(args, env=env, check=True, capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError):
        raise SigningError('APK signing/verification failed. Check the original key and its credentials.') from None


def verify_certificate(output):
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', output)
    if [c.lower() for c in certificates] != [EXPECTED_CERT_SHA256]:
        raise SigningError('APK signer does not match the pinned original certificate; refusing to publish.')


def sign_apk(apk, build_tools, raw):
    data, key_bytes = read_credentials(raw)
    apk = Path(apk).resolve()
    if not apk.is_file():
        raise SigningError('Built APK is missing.')
    env = dict(os.environ)
    env.pop(SECRET_NAME, None)
    env['MC_STORE_PASSWORD'] = data['storePassword']
    env['MC_KEY_PASSWORD'] = data['keyPassword']
    signer = str(Path(build_tools) / 'apksigner')
    # Keep the key outside the checkout, artifacts and dependency cache. Remove
    # it on success and failure. The original APK changes only after verification.
    with tempfile.TemporaryDirectory(prefix='mobile-codex-signing-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        key = Path(directory) / 'original.keystore'
        with key.open('xb') as stream:
            key.chmod(0o600)
            stream.write(key_bytes)
        fd, name = tempfile.mkstemp(prefix='.signed-', suffix='.apk', dir=apk.parent)
        os.close(fd)
        pending = Path(name)
        try:
            checked_run([signer, 'sign', '--ks', str(key), '--ks-key-alias', data['keyAlias'],
                         '--ks-pass', 'env:MC_STORE_PASSWORD', '--key-pass', 'env:MC_KEY_PASSWORD',
                         '--v4-signing-enabled', 'false', '--out', str(pending), str(apk)], env)
            result = checked_run([signer, 'verify', '--print-certs', str(pending)], env)
            verify_certificate(result)
            checked_run([str(Path(build_tools) / 'zipalign'), '-c', '-P', '16', '4', str(pending)], env)
            pending.replace(apk)
        finally:
            pending.unlink(missing_ok=True)
    print('APK signature verified against the pinned original certificate: ' + EXPECTED_CERT_SHA256)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-config', action='store_true')
    parser.add_argument('--apk', type=Path, default=Path('app/build/outputs/apk/debug/app-debug.apk'))
    parser.add_argument('--build-tools', type=Path)
    args = parser.parse_args()
    try:
        raw = os.environ.get(SECRET_NAME, '')
        if args.check_config:
            read_credentials(raw)
            print('Signing secret is configured.')
        else:
            if not args.build_tools:
                parser.error('--build-tools is required when signing')
            sign_apk(args.apk, args.build_tools, raw)
    except SigningError as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()

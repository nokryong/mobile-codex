import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_signing', Path(__file__).parents[1] / 'tools/sign_ci_apk.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class CiSigningTests(unittest.TestCase):
    def secret(self):
        return json.dumps(dict(keystoreBase64=base64.b64encode(b'fixture-key').decode(),
                              keyAlias='fixture-alias', storePassword='secret-store', keyPassword='secret-key'))

    def test_absent_or_invalid_secret_cannot_fall_back_to_random_debug_key(self):
        for raw in ('', 'not-json', '[]', '{}', self.secret().replace('Zml4dHVyZS1rZXk=', '%%%')):
            with self.subTest(raw=raw), self.assertRaises(signing.SigningError):
                signing.read_credentials(raw)

    def test_wrong_signer_preserves_original_apk_and_removes_temporary_key(self):
        self.check_signing(wrong_signer=True)

    def test_expected_signer_replaces_apk_after_verification_and_cleans_key(self):
        self.check_signing(wrong_signer=False)

    def check_signing(self, wrong_signer):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); apk = root / 'app.apk'; apk.write_bytes(b'original')
            key_paths = []
            def run(args, env):
                self.assertNotIn(signing.SECRET_NAME, env)
                self.assertNotIn('secret-store', args)
                self.assertNotIn('secret-key', args)
                if args[1] == 'sign':
                    key = Path(args[args.index('--ks') + 1]); key_paths.append(key)
                    self.assertEqual(key.read_bytes(), b'fixture-key')
                    self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                    Path(args[args.index('--out') + 1]).write_bytes(b'signed')
                    return ''
                if args[1] == 'verify':
                    digest = '0' * 64 if wrong_signer else signing.EXPECTED_CERT_SHA256
                    return 'Signer #1 certificate SHA-256 digest: ' + digest
                return ''
            with patch.dict(os.environ, {signing.SECRET_NAME: self.secret(), 'RUNNER_TEMP': str(root)}), patch.object(signing, 'checked_run', side_effect=run):
                if wrong_signer:
                    with self.assertRaises(signing.SigningError):
                        signing.sign_apk(apk, root, self.secret())
                else:
                    signing.sign_apk(apk, root, self.secret())
            self.assertEqual(apk.read_bytes(), b'original' if wrong_signer else b'signed')
            self.assertTrue(key_paths)
            self.assertTrue(all(not p.exists() for p in key_paths))
            self.assertEqual(list(root.iterdir()), [apk])

    def test_multiple_or_missing_signers_are_rejected(self):
        good = 'Signer #1 certificate SHA-256 digest: ' + signing.EXPECTED_CERT_SHA256
        for output in ('', good + '\n' + good):
            with self.assertRaises(signing.SigningError):
                signing.verify_certificate(output)

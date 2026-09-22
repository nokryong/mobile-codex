import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('prepare_update_release',Path(__file__).parents[1]/'tools/prepare_update_release.py')
release=importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
class UpdateReleaseTests(unittest.TestCase):
    def test_verified_metadata_comes_from_apk_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/'app.apk';apk.write_bytes(b'apk fixture')
            badging="package: name='dev.mobilecodex.app' versionCode='12' versionName='0.1.11-alpha'\nsdkVersion:'29'\nnative-code: 'arm64-v8a'"
            result=release.metadata(badging,'Signer #1 certificate SHA-256 digest: '+'ab'*32,apk)
            self.assertEqual(result['versionCode'],12);self.assertEqual(result['size'],11);self.assertEqual(result['fileName'],'mobile-codex-0.1.11-alpha-arm64.apk')
            self.assertEqual(result['signingCertificateSha256'],['ab'*32])
            modern = badging.replace("sdkVersion:'29'", "minSdkVersion:'29'\ntargetSdkVersion:'35'")
            self.assertEqual(release.metadata(modern, 'Signer #1 certificate SHA-256 digest: '+'ab'*32, apk), result)
            with self.assertRaisesRegex(ValueError, 'minimum SDK'):
                release.metadata(badging.replace("sdkVersion:'29'", "targetSdkVersion:'35'"), 'Signer #1 certificate SHA-256 digest: '+'ab'*32, apk)
            for text,sign in [(badging.replace('dev.mobilecodex.app','other.app'),'Signer #1 certificate SHA-256 digest: '+'ab'*32),(badging,''),(badging.replace('arm64-v8a','x86_64'),'Signer #1 certificate SHA-256 digest: '+'ab'*32)]:
                with self.assertRaises(ValueError):release.metadata(text,sign,apk)

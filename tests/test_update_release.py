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
            badging="package: name='dev.mobilecodex.app' versionCode='68' versionName='0.3.3'\nsdkVersion:'29'\nnative-code: 'arm64-v8a'"
            signer='Signer #1 certificate SHA-256 digest: '+release.POLICY['signingCertificateSha256'][0]
            manifest='E: manifest\n  E: application\n    A: android:debuggable(0x0101000f)=(type 0x12)0x0'
            result=release.metadata(badging,signer,apk,manifest)
            self.assertEqual(result['versionCode'],68);self.assertEqual(result['size'],11);self.assertEqual(result['fileName'],'mobile-codex-0.3.3-arm64.apk')
            self.assertEqual(result['signingCertificateSha256'],release.POLICY['signingCertificateSha256'])
            self.assertIs(result['debuggable'],False);self.assertIs(result['testOnly'],False)
            modern = badging.replace("sdkVersion:'29'", "minSdkVersion:'29'\ntargetSdkVersion:'35'")
            self.assertEqual(release.metadata(modern, signer, apk, manifest), result)
            with self.assertRaisesRegex(ValueError, 'minimum SDK'):
                release.metadata(badging.replace("sdkVersion:'29'", "targetSdkVersion:'35'"), signer, apk, manifest)
            for text,sign in [(badging.replace('dev.mobilecodex.app','other.app'),signer),(badging,''),(badging.replace('arm64-v8a','x86_64'),signer),
                              (badging+'\napplication-debuggable',signer),(badging+'\napplication-testOnly',signer),
                              (badging.replace('68','67'),signer),(badging,signer.replace(release.POLICY['signingCertificateSha256'][0],'ab'*32)),
                              (badging.replace("'arm64-v8a'","'arm64-v8a' 'x86_64'"),signer),(badging.replace("sdkVersion:'29'","sdkVersion:'30'"),signer)]:
                with self.subTest(text=text), self.assertRaises(ValueError):release.metadata(text,sign,apk,manifest)
            for flag in ('debuggable','testOnly'):
                with self.assertRaises(ValueError):
                    release.metadata(badging,signer,apk,'E: manifest\n E: application\n A: android:'+flag+'(0x0101000f)=(type 0x12)0xffffffff')
            with self.assertRaises(ValueError):release.metadata(badging,signer,apk,manifest,previous_version_code=68)
            with self.assertRaises(ValueError):release.metadata(badging,signer,apk,manifest,expected=dict(versionCode=69,versionName='0.3.3'))

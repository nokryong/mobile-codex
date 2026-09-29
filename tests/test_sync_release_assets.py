import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import subprocess


TOOLS = Path(__file__).parents[1] / 'tools'
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location('sync_release_assets', TOOLS / 'sync_release_assets.py')
sync = importlib.util.module_from_spec(spec); spec.loader.exec_module(sync)


class SyncReleaseAssetsTests(unittest.TestCase):
    version = '0.2.0'

    def setUp(self):
        sync.WORKFLOW_TREE_CACHE.clear()

    def release(self, files, body='canonical notes'):
        assets = []
        for name, path in files.items():
            assets.append({'name': name, 'size': path.stat().st_size, 'digest': 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()})
        return {'draft': False, 'assets': assets, 'body': body}

    def write_files(self, root, *, remote_version=None):
        version = remote_version or self.version
        apk = f'mobile-codex-{version}-arm64.apk'
        files = {
            apk: b'canonical apk',
            apk + '.sha256': b'',
            'mobile-codex-update.json': b'',
            sync.SOURCE_ZIP: b'canonical corresponding sources',
            'LICENSE': b'license text\n',
            'THIRD_PARTY_NOTICES.md': b'notices text\n',
        }
        digest = hashlib.sha256(files[apk]).hexdigest()
        metadata = {'versionName': version, 'versionCode': 200, 'applicationId': 'dev.mobilecodex.app',
                    'fileName': apk, 'sha256': digest, 'size': len(files[apk]),
                    'signingCertificateSha256': [sync.EXPECTED_CERT_SHA256]}
        files[apk + '.sha256'] = (digest + '  ' + apk + '\n').encode()
        files['mobile-codex-update.json'] = (json.dumps(metadata) + '\n').encode()
        paths = {}
        for name, value in files.items():
            path = root / name; path.write_bytes(value); paths[name] = path
        return paths, metadata

    def test_release_requires_exact_named_sized_digested_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            files, _ = self.write_files(Path(directory))
            release = self.release(files)
            validated = sync.validate_release_assets(release, sync.release_names(self.version))
            self.assertEqual(set(validated), set(sync.release_names(self.version)))
            release['assets'].pop()
            with self.assertRaises(ValueError):
                sync.validate_release_assets(release, sync.release_names(self.version))
            release = self.release(files); release['assets'][0]['digest'] = 'sha256:' + '0' * 64
            self.assertEqual(71, len(release['assets'][0]['digest']))
            # Digest syntax passes the shape check; byte verification rejects it.
            with tempfile.TemporaryDirectory() as stage:
                shutil.copytree(directory, stage, dirs_exist_ok=True)
                with self.assertRaises(ValueError):
                    sync.validate_downloads(Path(stage), {a['name']: a for a in release['assets']}, sync.release_names(self.version))

    def test_non_404_canonical_release_error_is_not_retried_as_missing(self):
        error = subprocess.CalledProcessError(1, ['gh', 'api'], stderr='HTTP 401 Unauthorized')
        with patch.object(sync, 'gh', side_effect=error):
            with self.assertRaises(subprocess.CalledProcessError):
                sync.canonical_release('v0.2.0')

    def test_checked_out_identity_accepts_only_literal_unique_gradle_values(self):
        with tempfile.TemporaryDirectory() as directory:
            gradle = Path(directory) / 'build.gradle'
            gradle.write_text("""android {
                defaultConfig {
                    applicationId 'dev.mobilecodex.app'
                    versionCode 200
                    versionName '0.2.0'
                }
            }
            """)
            self.assertEqual({
                'applicationId': 'dev.mobilecodex.app', 'versionCode': 200,
                'versionName': '0.2.0', 'fileName': 'mobile-codex-0.2.0-arm64.apk',
                'signingCertificateSha256': [sync.EXPECTED_CERT_SHA256],
            }, sync.checked_out_identity(gradle))
            gradle.write_text(gradle.read_text().replace("versionName '0.2.0'", 'versionName projectVersion'))
            with self.assertRaises(ValueError):
                sync.checked_out_identity(gradle)

    def test_wait_stops_immediately_for_matching_canonical_failure(self):
        failed = {'conclusion': 'cancelled', 'html_url': 'https://example.test/run'}
        with patch.object(sync, 'canonical_release', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'cancelled'):
                sync.wait_for_release('v0.2.0', timeout=600, interval=15,
                                      clock=lambda: 0, sleep=lambda _: self.fail('must not sleep'),
                                      expected_tree='a' * 40, failure_check=lambda tree: failed)

    def test_failed_workflow_ignores_different_tree_and_newer_success(self):
        runs = {
            'workflow_runs': [
                {'id': 1, 'created_at': '2026-01-01T00:00:00Z', 'head_branch': 'main',
                 'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'failure'},
                {'id': 2, 'created_at': '2026-01-02T00:00:00Z', 'head_branch': 'main',
                 'head_sha': 'b' * 40, 'status': 'completed', 'conclusion': 'success'},
            ]
        }
        def fake_api(path):
            if 'actions/workflows/android.yml/runs?' in path:
                return runs
            if path.endswith('a' * 40):
                return {'tree': {'sha': '1' * 40}}
            if path.endswith('b' * 40):
                return {'tree': {'sha': '2' * 40}}
            self.fail(path)
        with patch.object(sync, 'api', side_effect=fake_api):
            self.assertIsNone(sync.failed_canonical_workflow('2' * 40))

    def test_failed_workflow_keeps_waiting_after_same_tree_retry_is_running_or_successful(self):
        for newest_status, newest_conclusion in (('in_progress', None), ('completed', 'success')):
            with self.subTest(status=newest_status, conclusion=newest_conclusion):
                runs = {'workflow_runs': [
                    {'id': 1, 'created_at': '2026-01-01T00:00:00Z', 'head_branch': 'main',
                     'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'failure'},
                    {'id': 2, 'created_at': '2026-01-02T00:00:00Z', 'head_branch': 'main',
                     'head_sha': 'b' * 40, 'status': newest_status, 'conclusion': newest_conclusion},
                ]}
                def fake_api(path):
                    if 'actions/workflows/android.yml/runs?' in path:
                        return runs
                    if path.endswith(('a' * 40, 'b' * 40)):
                        return {'tree': {'sha': '2' * 40}}
                    self.fail(path)
                with patch.object(sync, 'api', side_effect=fake_api):
                    self.assertIsNone(sync.failed_canonical_workflow('2' * 40))
                sync.WORKFLOW_TREE_CACHE.clear()

    def test_failed_workflow_ignores_a_failure_for_another_tree(self):
        runs = {'workflow_runs': [
            {'id': 1, 'created_at': '2026-01-01T00:00:00Z', 'head_branch': 'main',
             'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'failure'},
        ]}
        def fake_api(path):
            if 'actions/workflows/android.yml/runs?' in path:
                return runs
            if path.endswith('a' * 40):
                return {'tree': {'sha': '1' * 40}}
            self.fail(path)
        with patch.object(sync, 'api', side_effect=fake_api):
            self.assertIsNone(sync.failed_canonical_workflow('2' * 40))

    def test_failed_workflow_returns_matching_failure(self):
        failure = {'id': 7, 'created_at': '2026-01-03T00:00:00Z', 'head_branch': 'main',
                   'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'failure'}
        def fake_api(path):
            if 'actions/workflows/android.yml/runs?' in path:
                return {'workflow_runs': [failure]}
            if path.endswith('a' * 40):
                return {'tree': {'sha': '2' * 40}}
            self.fail(path)
        with patch.object(sync, 'api', side_effect=fake_api):
            self.assertEqual(failure, sync.failed_canonical_workflow('2' * 40))

    def test_sync_rejects_different_tree_before_download_or_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'artifacts/update').mkdir(parents=True)
            _, metadata = self.write_files(root)
            (root / 'artifacts/update/mobile-codex-update.json').write_text(json.dumps(metadata))
            with patch.object(sync, 'wait_for_release', return_value={'assets': []}), patch.object(sync, 'canonical_tag_tree', return_value='a' * 40), patch.object(sync, 'local_tree', return_value='b' * 40), patch.object(sync, 'download_assets') as download:
                with self._cwd(root), self.assertRaises(ValueError):
                    sync.sync(Path('/tools'))
                download.assert_not_called()

    def test_failed_identity_validation_leaves_generated_files_untouched(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as remote_dir:
            root, remote = Path(directory), Path(remote_dir)
            (root / 'artifacts/update').mkdir(parents=True); (root / 'app/build/outputs/apk/debug').mkdir(parents=True)
            files, metadata = self.write_files(remote, remote_version='0.2.1')
            # Local version is 0.2.0, so the mirror never reaches replacement.
            local = dict(metadata); local['versionName'] = self.version; local['fileName'] = f'mobile-codex-{self.version}-arm64.apk'
            (root / 'artifacts/update/mobile-codex-update.json').write_text(json.dumps(local))
            target = root / 'app/build/outputs/apk/debug/app-debug.apk'; target.write_bytes(b'own generated apk')
            release = self.release(files)
            def download(tag, destination, names):
                for name in names:
                    source = files.get(name)
                    if source is not None: shutil.copyfile(source, destination / name)
            with self._cwd(root), patch.object(sync, 'wait_for_release', return_value=release), patch.object(sync, 'canonical_tag_tree', return_value='a' * 40), patch.object(sync, 'local_tree', return_value='a' * 40), patch.object(sync, 'download_assets', side_effect=download):
                with self.assertRaises(ValueError): sync.sync(Path('/tools'))
            self.assertEqual(b'own generated apk', target.read_bytes())

    def test_validated_mirror_replaces_only_generated_assets_and_keeps_legal_source(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as remote_dir:
            root, remote = Path(directory), Path(remote_dir)
            (root / 'artifacts/update').mkdir(parents=True); (root / 'app/build/outputs/apk/debug').mkdir(parents=True)
            files, metadata = self.write_files(remote)
            local = dict(metadata); local['sha256'] = '0' * 64; local['size'] = 9
            # Independent private builds embed their own SOURCE_SHA, so these
            # are expected to differ before canonical replacement.
            (root / 'artifacts/update/mobile-codex-update.json').write_text(json.dumps(local))
            (root / 'app/build/outputs/apk/debug/app-debug.apk').write_bytes(b'old apk')
            (root / 'LICENSE').write_bytes(files['LICENSE'].read_bytes())
            (root / 'THIRD_PARTY_NOTICES.md').write_bytes(files['THIRD_PARTY_NOTICES.md'].read_bytes())
            release = self.release(files)
            def download(tag, destination, names):
                for name in names: shutil.copyfile(files[name], destination / name)
            with self._cwd(root), patch.object(sync, 'wait_for_release', return_value=release), patch.object(sync, 'canonical_tag_tree', return_value='a' * 40), patch.object(sync, 'local_tree', return_value='a' * 40), patch.object(sync, 'download_assets', side_effect=download), patch.object(sync, 'inspect_apk', return_value=metadata):
                sync.sync(Path('/tools'))
            self.assertEqual(files[f'mobile-codex-{self.version}-arm64.apk'].read_bytes(), (root / 'app/build/outputs/apk/debug/app-debug.apk').read_bytes())
            self.assertEqual(files[f'mobile-codex-{self.version}-arm64.apk'].read_bytes(), (root / 'artifacts/update/mobile-codex-0.2.0-arm64.apk').read_bytes())
            self.assertEqual(files['LICENSE'].read_bytes(), (root / 'LICENSE').read_bytes())
            self.assertEqual('canonical notes', (root / 'artifacts/canonical-release-notes.md').read_text())

    def test_without_build_uses_gradle_identity_and_never_needs_local_apk(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as remote_dir:
            root, remote = Path(directory), Path(remote_dir)
            (root / 'app').mkdir()
            (root / 'app/build.gradle').write_text("""android {
                defaultConfig {
                    applicationId 'dev.mobilecodex.app'
                    versionCode 200
                    versionName '0.2.0'
                }
            }
            """)
            files, metadata = self.write_files(remote)
            (root / 'LICENSE').write_bytes(files['LICENSE'].read_bytes())
            (root / 'THIRD_PARTY_NOTICES.md').write_bytes(files['THIRD_PARTY_NOTICES.md'].read_bytes())
            release = self.release(files)
            def download(tag, destination, names):
                for name in names:
                    shutil.copyfile(files[name], destination / name)
            with self._cwd(root), patch.object(sync, 'wait_for_release', return_value=release), \
                    patch.object(sync, 'canonical_tag_tree', return_value='a' * 40), \
                    patch.object(sync, 'local_tree', return_value='a' * 40), \
                    patch.object(sync, 'download_assets', side_effect=download), \
                    patch.object(sync, 'inspect_apk', return_value=metadata):
                sync.sync_without_build(Path('/tools'))
            apk = root / 'artifacts/update/mobile-codex-0.2.0-arm64.apk'
            self.assertEqual(files[apk.name].read_bytes(), apk.read_bytes())
            self.assertFalse((root / 'app/build/outputs/apk/debug/app-debug.apk').exists())
            self.assertEqual(files[sync.SOURCE_ZIP].read_bytes(), (root / 'artifacts' / sync.SOURCE_ZIP).read_bytes())
            self.assertEqual('canonical notes', (root / 'artifacts/canonical-release-notes.md').read_text())

    def test_without_build_rejects_different_tree_before_download(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'app').mkdir()
            (root / 'app/build.gradle').write_text("""android {
                defaultConfig {
                    applicationId 'dev.mobilecodex.app'
                    versionCode 200
                    versionName '0.2.0'
                }
            }
            """)
            with self._cwd(root), patch.object(sync, 'wait_for_release', return_value={'assets': []}), \
                    patch.object(sync, 'canonical_tag_tree', return_value='a' * 40), \
                    patch.object(sync, 'local_tree', return_value='b' * 40), \
                    patch.object(sync, 'download_assets') as download:
                with self.assertRaises(ValueError):
                    sync.sync_without_build(Path('/tools'))
            download.assert_not_called()

    class _cwd:
        def __init__(self, path): self.path, self.previous = path, None
        def __enter__(self):
            import os
            self.previous = Path.cwd(); os.chdir(self.path); return self
        def __exit__(self, *unused):
            import os
            os.chdir(self.previous)


if __name__ == '__main__':
    unittest.main()

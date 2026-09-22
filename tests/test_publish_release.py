import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_release', Path(__file__).parents[1] / 'tools/publish_release.py')
release = importlib.util.module_from_spec(spec);spec.loader.exec_module(release)

class PublishReleaseTests(unittest.TestCase):
    def test_existing_public_version_is_not_overwritten(self):
        env={'GITHUB_REPOSITORY':'example/app','GITHUB_SHA':'abc','GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'push'}
        with patch.dict(os.environ,env), patch.object(release,'validate_assets',return_value=({'versionName':'0.1.12-alpha'},[])), patch.object(release,'gh',return_value=json.dumps([[{'tag_name':'v0.1.12-alpha','draft':False}]])) as gh:
            release.publish();self.assertEqual(gh.call_count,1)
    def test_pull_requests_cannot_publish(self):
        with patch.dict(os.environ,{'GITHUB_REPOSITORY':'example/app','GITHUB_SHA':'abc','GITHUB_REF':'refs/pull/1/merge','GITHUB_EVENT_NAME':'pull_request'}), patch.object(release,'gh') as gh:
            with self.assertRaises(ValueError):release.publish()
            gh.assert_not_called()
    def test_mismatched_apk_is_rejected_before_any_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);(folder/'mobile-codex-0.1.12-alpha-arm64.apk').write_bytes(b'changed')
            (folder/'mobile-codex-update.json').write_text(json.dumps({'versionName':'0.1.12-alpha','applicationId':'dev.mobilecodex.app','fileName':'mobile-codex-0.1.12-alpha-arm64.apk','sha256':hashlib.sha256(b'original').hexdigest(),'size':7}))
            with self.assertRaises(ValueError):release.validate_assets(folder)
    def test_upload_failure_leaves_draft_unpublished(self):
        env={'GITHUB_REPOSITORY':'example/app','GITHUB_SHA':'abc','GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'push'}
        def gh(*args):
            if args[0]=='api':return '[[]]'
            if args[:2]==('release','upload'):raise RuntimeError('upload failed')
            return ''
        with patch.dict(os.environ,env),patch.object(release,'validate_assets',return_value=({'versionName':'0.1.12-alpha','fileName':'app.apk'},[Path('app.apk')])),patch.object(release,'gh',side_effect=gh) as call:
            with self.assertRaises(RuntimeError):release.publish()
            self.assertFalse(any(c.args[:2]==('release','edit') for c in call.call_args_list))

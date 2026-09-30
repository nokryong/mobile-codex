import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch


ROOT = Path(__file__).parents[1]
WORKFLOW = (ROOT / '.github/workflows/android.yml').read_text(encoding='utf-8')
STEP = re.search(
    r'(?ms)^      - name: Publish private pull request test build\n(.*?)(?=^      - (?:name:|uses:)|\Z)',
    WORKFLOW,
).group(1)
SCRIPT = textwrap.dedent(STEP.split("          python3 - <<'PY'\n", 1)[1].split('\n          PY', 1)[0])
spec = importlib.util.spec_from_file_location('private_pr_release_assets', ROOT / 'tools/publish_release.py')
release_assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_assets)


class PrivatePrApkWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.update = self.root / 'artifacts/update'
        self.update.mkdir(parents=True)
        self.apk = self.update / 'mobile-codex-0.2.8-arm64.apk'
        self.apk.write_bytes(b'signed APK fixture')
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.metadata = dict(versionName='0.2.8', applicationId='dev.mobilecodex.app', fileName=self.apk.name,
                             sha256=digest, size=self.apk.stat().st_size)
        (self.update / 'mobile-codex-update.json').write_text(json.dumps(self.metadata), encoding='utf-8')
        (self.update / (self.apk.name + '.sha256')).write_text(digest + '  ' + self.apk.name, encoding='utf-8')
        (self.root / 'artifacts/devtools-corresponding-source.zip').write_bytes(b'separate source archive')
        (self.root / 'LICENSE').write_text('license', encoding='utf-8')
        (self.root / 'THIRD_PARTY_NOTICES.md').write_text('notices', encoding='utf-8')
        self.event_path = self.root / 'event.json'
        self.event = dict(number=2, repository=dict(full_name='SeeUSoon93/mobile-codex', private=True),
                          pull_request=dict(head=dict(repo=dict(full_name='SeeUSoon93/mobile-codex')),
                                            base=dict(repo=dict(full_name='SeeUSoon93/mobile-codex'))))
        self.env = dict(GITHUB_REPOSITORY='SeeUSoon93/mobile-codex', GITHUB_EVENT_NAME='pull_request',
                        PRIVATE_TEST_PR='true', GITHUB_REF='refs/pull/2/merge', GITHUB_RUN_ID='12345',
                        GITHUB_RUN_ATTEMPT='1', GITHUB_SHA='a' * 40, GITHUB_EVENT_PATH=str(self.event_path),
                        GITHUB_STEP_SUMMARY=str(self.root / 'summary.md'))
        self.commands = []
        self.notes = []

    def run_script(self, env=None, event=None, actual_private=True, fail_upload=False, incomplete_upload=False):
        self.event_path.write_text(json.dumps(event if event is not None else self.event), encoding='utf-8')

        def gh(command, **kwargs):
            self.assertTrue(kwargs['check'])
            self.assertEqual('gh', command[0])
            self.commands.append(command)
            if command[1] == 'api':
                self.assertEqual('repos/SeeUSoon93/mobile-codex', command[2])
                result = dict(full_name='SeeUSoon93/mobile-codex', private=actual_private)
            elif command[1:3] == ['release', 'create']:
                self.notes.append(Path(command[command.index('--notes-file') + 1]).read_text(encoding='utf-8'))
                result = None
            elif command[1:3] == ['release', 'upload']:
                if fail_upload:
                    raise subprocess.CalledProcessError(1, command)
                result = None
            elif command[1:3] == ['release', 'view']:
                _, assets = release_assets.validate_assets(Path('artifacts/update'))
                result = dict(assets=[dict(name=asset.name, size=asset.stat().st_size) for asset in assets])
                if incomplete_upload:
                    result['assets'] = result['assets'][1:]
            else:
                result = None
            return subprocess.CompletedProcess(command, 0, stdout=json.dumps(result) if result is not None else '')

        old = Path.cwd()
        try:
            os.chdir(self.root)
            with patch.dict(os.environ, env if env is not None else self.env, clear=True), \
                    patch.dict(sys.modules, {'publish_release': release_assets}), \
                    patch.object(sys, 'path', list(sys.path)), patch('subprocess.run', side_effect=gh), \
                    patch('sys.stdout', new_callable=io.StringIO):
                exec(compile(SCRIPT, '<private-pr-apk-workflow>', 'exec'), {'__name__': '__main__'})
        finally:
            os.chdir(old)

    def release_commands(self, action):
        return [command for command in self.commands if command[1:3] == ['release', action]]

    def test_verified_private_build_has_raw_apk_and_separate_sources_and_summary(self):
        self.run_script()
        create = self.release_commands('create')[0]
        tag = 'private-pr-2-v0.2.8-run-12345-1'
        self.assertEqual(tag, create[3])
        self.assertIn('--draft', create)
        self.assertIn('--prerelease', create)
        self.assertEqual('a' * 40, create[create.index('--target') + 1])
        upload = self.release_commands('upload')[0]
        self.assertIn('artifacts/update/' + self.apk.name, upload)
        self.assertIn('artifacts/devtools-corresponding-source.zip', upload)
        self.assertEqual(1, sum(value.endswith('.apk') for value in upload))
        self.assertEqual(1, sum(value.endswith('.zip') for value in upload))
        edit = self.release_commands('edit')[0]
        self.assertIn('--draft=false', edit)
        self.assertIn('--latest=false', edit)
        self.assertTrue(all(command[command.index('--repo') + 1] == 'SeeUSoon93/mobile-codex'
                            for command in self.commands if '--repo' in command))
        self.assertIn('Download the .apk asset directly', self.notes[0])
        summary = (self.root / 'summary.md').read_text(encoding='utf-8')
        self.assertIn('https://github.com/SeeUSoon93/mobile-codex/releases/tag/' + tag, summary)

    def test_rerun_uses_a_new_tag_without_replacing_an_old_build(self):
        self.run_script()
        changed = dict(self.env, GITHUB_RUN_ATTEMPT='2')
        self.run_script(env=changed)
        tags = [command[3] for command in self.release_commands('create')]
        self.assertEqual(['private-pr-2-v0.2.8-run-12345-1', 'private-pr-2-v0.2.8-run-12345-2'], tags)
        self.assertFalse(any('--clobber' in command for command in self.commands))

    def test_untrusted_events_cannot_make_any_github_call(self):
        cases = []
        for key, value in [('GITHUB_REPOSITORY', 'nokryong/mobile-codex'),
                           ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_EVENT_NAME', 'workflow_dispatch'),
                           ('PRIVATE_TEST_PR', 'false'), ('GITHUB_REF', 'refs/heads/main'),
                           ('GITHUB_RUN_ID', '123;bad'), ('GITHUB_RUN_ATTEMPT', '0'), ('GITHUB_SHA', 'main')]:
            cases.append((dict(self.env, **{key: value}), self.event))
        public_event = copy.deepcopy(self.event)
        public_event['repository']['private'] = False
        cases.append((self.env, public_event))
        fork_event = copy.deepcopy(self.event)
        fork_event['pull_request']['head']['repo']['full_name'] = 'someone/mobile-codex'
        cases.append((self.env, fork_event))
        other_base = copy.deepcopy(self.event)
        other_base['pull_request']['base']['repo']['full_name'] = 'nokryong/mobile-codex'
        cases.append((self.env, other_base))
        invalid_number = copy.deepcopy(self.event)
        invalid_number['number'] = True
        cases.append((self.env, invalid_number))
        for env, event in cases:
            with self.subTest(env=env, event=event):
                self.commands.clear()
                with self.assertRaises(ValueError):
                    self.run_script(env=env, event=event)
                self.assertEqual([], self.commands)

    def test_actual_public_visibility_rejects_writes_even_if_event_claims_private(self):
        with self.assertRaises(ValueError):
            self.run_script(actual_private=False)
        self.assertEqual(1, len(self.commands))
        self.assertEqual('api', self.commands[0][1])

    def test_changed_apk_is_rejected_before_release_creation(self):
        self.apk.write_bytes(b'changed APK')
        with self.assertRaises(ValueError):
            self.run_script()
        self.assertFalse(self.release_commands('create'))
        self.assertFalse(self.release_commands('upload'))

    def test_upload_failure_keeps_draft_and_does_not_report_success(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_script(fail_upload=True)
        self.assertIn('--draft', self.release_commands('create')[0])
        self.assertFalse(self.release_commands('edit'))
        self.assertFalse((self.root / 'summary.md').exists())

    def test_missing_remote_asset_keeps_draft(self):
        with self.assertRaises(ValueError):
            self.run_script(incomplete_upload=True)
        self.assertFalse(self.release_commands('edit'))

    def test_workflow_requires_full_checks_signed_assets_and_private_guard(self):
        private_guard = ("github.repository == 'SeeUSoon93/mobile-codex' && github.event.repository.private == true "
                         "&& github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository")
        self.assertIn('PRIVATE_TEST_PR: ${{ ' + private_guard + ' }}', WORKFLOW)
        self.assertIn("if: env.PRIVATE_TEST_PR == 'true' && !inputs.apk_only", STEP)
        self.assertIn('needs: checks', WORKFLOW)
        self.assertLess(WORKFLOW.index('- name: Build and check Android app'), WORKFLOW.index('- name: Publish private pull request test build'))
        self.assertLess(WORKFLOW.index('- name: Sign APK with the original installation key'), WORKFLOW.index('- name: Publish private pull request test build'))
        self.assertLess(WORKFLOW.index('- name: Prepare verified update assets'), WORKFLOW.index('- name: Publish private pull request test build'))
        self.assertLess(WORKFLOW.index('- name: Publish private pull request test build'), WORKFLOW.index('- name: Upload private pull request test APK'))

    def test_artifact_quota_is_optional_only_for_trusted_private_prs(self):
        steps = re.findall(r'(?ms)^      - .*?(?=^      - |\Z)', WORKFLOW)
        def artifact(name):
            return next(step for step in steps if 'actions/upload-artifact@' in step and '\n          name: ' + name + '\n' in step)
        ui = artifact('ui-check-reports')
        self.assertIn("continue-on-error: ${{ github.repository == 'SeeUSoon93/mobile-codex'", ui)
        self.assertIn("github.event.repository.private == true && github.event_name == 'pull_request'", ui)
        self.assertIn('github.event.pull_request.head.repo.full_name == github.repository', ui)
        for name in ['mobile-codex-private-pr-test-apk', 'mobile-codex-private-pr-tool-sources']:
            self.assertIn("if: env.PRIVATE_TEST_PR == 'true'", artifact(name))
            self.assertIn('continue-on-error: true', artifact(name))
        self.assertIn("continue-on-error: ${{ env.PRIVATE_TEST_PR == 'true' }}", artifact('android-check-reports'))
        self.assertNotIn('continue-on-error:', artifact('mobile-codex-test-apk'))
        public_release = next(step for step in steps if '- name: Publish versioned GitHub release' in step)
        self.assertIn("github.ref == 'refs/heads/main' && github.event_name != 'pull_request' && !inputs.apk_only", public_release)
        self.assertNotIn('continue-on-error:', public_release)


if __name__ == '__main__':
    unittest.main()

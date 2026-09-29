import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('reuse_ui_checks', Path(__file__).parents[1] / 'tools/reuse_ui_checks.py')
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)

class ReuseUiChecksTests(unittest.TestCase):
    def test_android_test_fix_can_reuse_but_app_ui_and_packaging_changes_cannot(self):
        base = b'100644 blob aaa\tapp/src/main/assets/web/app.js\0'
        self.assertEqual(reuse.fingerprint(base), reuse.fingerprint(base + b'100644 blob bbb\tapp/src/test/java/Test.java\0'))
        for path in ['app/src/main/java/Engine.java', 'tools/prepare_update_release.py', 'tests/ui.test.cjs', 'package-lock.json']:
            with self.subTest(path=path):
                self.assertNotEqual(reuse.fingerprint(base), reuse.fingerprint(base + b'100644 blob bbb\t' + path.encode() + b'\0'))
        self.assertNotEqual(reuse.fingerprint(base), reuse.fingerprint(base.replace(b'aaa', b'bbb')))

    def test_native_source_fix_reuses_browser_only(self):
        base = b'100644 blob aaa\tapp/src/main/assets/web/app.js\0'
        changed = base + b'100644 blob bbb\tapp/src/main/java/MainActivity.java\0'
        self.assertNotEqual(reuse.fingerprint(base), reuse.fingerprint(changed))
        self.assertEqual(reuse.fingerprint(base, browser=True), reuse.fingerprint(changed, browser=True))
        self.assertNotEqual(reuse.fingerprint(base, browser=True), reuse.fingerprint(base.replace(b'aaa', b'bbb'), browser=True))

    def test_skipped_or_missing_validation_is_not_success(self):
        steps = [{'name': name, 'conclusion': 'success'} for name in ['Test UI logic', 'Test packaging and release logic', 'Check responsive browser layouts']]
        job = {'name': 'Fast UI and source checks', 'conclusion': 'success', 'steps': steps}
        self.assertTrue(reuse.passed([job]))
        steps[-1]['conclusion'] = 'skipped'
        self.assertFalse(reuse.passed([job]))
        steps[-1]['conclusion'] = 'success'
        job['conclusion'] = 'cancelled'
        self.assertFalse(reuse.passed([job]))

    def test_changed_validation_commands_invalidate_reuse(self):
        workflow = '  checks:\n    steps:\n      - run: npm test\n  build:\n'
        conditional = workflow.replace('      - run:', "      # UI_REUSE_START\n      - run: gate\n      # UI_REUSE_END\n      - run:").replace('npm test\n', "npm test\n        if: steps.reuse.outputs.reused != 'true'\n")
        self.assertEqual(reuse.check_contract(workflow), reuse.check_contract(conditional))
        self.assertNotEqual(reuse.check_contract(workflow), reuse.check_contract(workflow.replace('npm test', 'true')))

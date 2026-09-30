import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('tested_before', Path(__file__).parents[1] / 'tools/tested_before.py')
tested = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tested)

GRADLE = b"android {\n    defaultConfig {\n        versionCode 52\n        versionName '0.2.6-alpha.2'\n    }\n}\n"
PACKAGE = json.dumps({'name': 'x', 'version': '0.2.6-alpha.2', 'devDependencies': {'jsdom': '26.1.0'}}).encode()
LOCK = json.dumps({'name': 'x', 'version': '0.2.6-alpha.2', 'packages': {'': {'version': '0.2.6-alpha.2'}, 'node_modules/jsdom': {'version': '26.1.0'}}}).encode()


def tree(blobs):
    return b''.join(b'100644 blob ' + key.encode() + b'\t' + path.encode() + b'\0' for path, key in blobs.items())


class TestedBeforeTests(unittest.TestCase):
    def fingerprint(self, files):
        # Like git, a blob name is derived from its content.
        key = lambda content: hashlib.sha1(content).hexdigest()
        store = {key(content): content for content in files.values()}
        listing = tree({path: key(content) for path, content in files.items()})
        return tested.code_fingerprint(listing, store.__getitem__)

    def base(self):
        return {'app/build.gradle': GRADLE, 'package.json': PACKAGE, 'package-lock.json': LOCK, 'app/src/main/assets/web/app.js': b'code'}

    def test_version_bump_and_release_notes_are_already_tested(self):
        bumped = self.base()
        bumped['app/build.gradle'] = GRADLE.replace(b'52', b'53').replace(b'alpha.2', b'alpha.3')
        bumped['package.json'] = PACKAGE.replace(b'alpha.2', b'alpha.3')
        bumped['package-lock.json'] = LOCK.replace(b'alpha.2', b'alpha.3')
        bumped['docs/releases/0.2.6-alpha.3.md'] = b'notes'
        bumped['README.md'] = b'readme'
        self.assertEqual(self.fingerprint(self.base()), self.fingerprint(bumped))

    def test_any_code_or_dependency_change_runs_every_check(self):
        for path, content in [('app/src/main/assets/web/app.js', b'changed'),
                              ('app/build.gradle', GRADLE.replace(b'versionCode 52', b'versionCode 52\n        minSdk 30')),
                              ('package-lock.json', LOCK.replace(b'26.1.0', b'27.0.0')),
                              ('app/src/main/java/Engine.java', b'new'), ('tools/verify_ui_layout.cjs', b'new')]:
            with self.subTest(path=path):
                changed = self.base(); changed[path] = content
                self.assertNotEqual(self.fingerprint(self.base()), self.fingerprint(changed))

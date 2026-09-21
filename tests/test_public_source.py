"""Guard the neutral public source identity, including Android test directories."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PublicSourceTests(unittest.TestCase):
    def test_all_java_source_sets_use_neutral_package_paths(self):
        for source_set in ('main', 'test', 'androidTest'):
            root = ROOT / 'app/src' / source_set / 'java'
            files = list(root.rglob('*.java'))
            self.assertTrue(files, source_set)
            for path in files:
                relative = path.relative_to(root).as_posix()
                self.assertTrue(relative.startswith('dev/mobilecodex/app/'), relative)
                package = re.search(r'^package\s+([^;]+);', path.read_text(encoding='utf-8'), re.M)
                self.assertIsNotNone(package, relative)
                self.assertEqual(package.group(1).replace('.', '/'), str(Path(relative).parent).replace('\\', '/'))

    def test_default_application_identity_is_neutral(self):
        gradle = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')
        self.assertIn("namespace 'dev.mobilecodex.app'", gradle)
        self.assertIn("applicationId 'dev.mobilecodex.app'", gradle)

    def test_source_and_docs_do_not_embed_developer_home_paths(self):
        roots = [ROOT / 'app/src', ROOT / 'docs', ROOT / 'tools', ROOT / '.github']
        files = [ROOT / 'README.md', ROOT / 'app/build.gradle']
        for root in roots:
            files.extend(p for p in root.rglob('*') if p.is_file() and p.suffix in {'.java', '.md', '.py', '.c', '.yml', '.gradle'})
        for path in files:
            if '/assets/runtime/' in path.as_posix() or '/assets/devtools/' in path.as_posix():
                continue  # Third-party generated runtime notices are outside source control.
            self.assertIsNone(re.search(r'[A-Za-z]:[/\\]+Users[/\\]+[^/\\\s]+|/Users/[^/\s]+', path.read_text(encoding='utf-8')), str(path.relative_to(ROOT)))


if __name__ == '__main__':
    unittest.main()

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import update_runtime  # noqa: E402

LOCK = {'package': '@mmmbuto/codex-cli-termux', 'version': '0.155.1',
        'url': 'https://registry.npmjs.org/@mmmbuto/codex-cli-termux/-/codex-cli-termux-0.155.1.tgz',
        'integrity': 'sha512-old', 'source': 'https://github.com/DioNanos/codex-termux/tree/v0.155.1',
        'license': 'Apache-2.0', 'abi': 'arm64-v8a', 'minimumAndroidApi': 29}


def metadata(latest, integrity='sha512-new', tarball=None):
    tarball = tarball or f'https://registry.npmjs.org/@mmmbuto/codex-cli-termux/-/codex-cli-termux-{latest}.tgz'
    return {'dist-tags': {'latest': latest}, 'versions': {latest: {'dist': {'integrity': integrity, 'tarball': tarball}}}}


class UpdateRuntimeTests(unittest.TestCase):
    def test_termux_rebuilds_sort_after_their_upstream_release(self):
        keys = [update_runtime.version_key(v) for v in ['0.155.1', '0.156.1', '0.156.1-termux.1', '0.156.1-termux.2', '0.157.0']]
        self.assertEqual(keys, sorted(keys))
        self.assertIsNone(update_runtime.version_key('0.157.0-alpha.1'))

    def test_only_moves_forward_to_the_latest_tag(self):
        self.assertIsNone(update_runtime.newer_release(LOCK, metadata('0.155.1')))
        self.assertIsNone(update_runtime.newer_release(LOCK, metadata('0.153.3')))
        self.assertIsNone(update_runtime.newer_release(LOCK, metadata('0.160.0-beta.1')))
        version, dist = update_runtime.newer_release(LOCK, metadata('0.156.1-termux.1'))
        self.assertEqual(version, '0.156.1-termux.1')
        lock = update_runtime.updated_lock(LOCK, version, dist)
        self.assertEqual(lock['url'], dist['tarball'])
        self.assertEqual(lock['integrity'], 'sha512-new')
        self.assertEqual(lock['source'], 'https://github.com/DioNanos/codex-termux/tree/v0.156.1-termux.1')
        self.assertEqual(lock['abi'], 'arm64-v8a')

    def test_rejects_unverifiable_or_foreign_archives(self):
        with self.assertRaises(ValueError):
            update_runtime.newer_release(LOCK, metadata('0.157.0', integrity='sha1-weak'))
        with self.assertRaises(ValueError):
            update_runtime.newer_release(LOCK, metadata('0.157.0', tarball='https://example.test/codex.tgz'))

    def test_version_text_changes_only_runtime_mentions(self):
        text = ('`@mmmbuto/codex-cli-termux@0.155.1` (https://github.com/DioNanos/codex-termux/tree/v0.155.1)\n'
                'Android용 Codex 포트 0.155.1 · Apache-2.0 / Android Codex port 0.155.1\n'
                'Verified against app-server v0.155.1.')
        result = update_runtime.replace_version_text(text, '0.155.1', '0.157.0')
        self.assertIn('codex-cli-termux@0.157.0', result)
        self.assertIn('/tree/v0.157.0', result)
        self.assertIn('Codex 포트 0.157.0', result)
        self.assertIn('Codex port 0.157.0', result)
        self.assertIn('app-server v0.155.1', result)

    def test_real_lock_and_version_text_agree(self):
        import json
        lock = json.loads(update_runtime.LOCK.read_text())
        self.assertIsNotNone(update_runtime.version_key(lock['version']))
        html = (update_runtime.ROOT / 'app/src/main/assets/web/index.html').read_text(encoding='utf-8')
        self.assertIn(f"Codex 포트 {lock['version']}", html)


if __name__ == '__main__':
    unittest.main()

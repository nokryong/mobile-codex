import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('source_recovery', Path(__file__).parents[1] / 'tools/restore_devtools_sources.py')
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class SourceRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools/native').mkdir(parents=True)
        data = b'original source archive'
        digest = hashlib.sha256(data).hexdigest()
        record = {'url': 'https://example.test/source', 'sha256': digest}
        lock = {'recipeArchive': record, 'packages': [{'name': 'test', 'recipeArchive': record, 'sources': [record]}]}
        self.members = {'devtools-lock.json': json.dumps(lock).encode(),
                        'SOURCES.json': json.dumps(recovery.records_for(lock)).encode(),
                        'README.txt': b'notice', 'archives/' + digest: data}
        for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'tools/prepare_devtools.py',
                     'tools/prepare_devtools_sources.py', 'tools/build_native.py',
                     'tools/requirements-devtools.txt', 'tools/native/launcher.c']:
            self.members[name] = ('original ' + name).encode()
        for name, data in self.members.items():
            if name.startswith('archives/') or name in ('SOURCES.json', 'README.txt'):
                continue
            path = self.root / ('tools/devtools-lock.json' if name == 'devtools-lock.json' else name)
            path.write_bytes(data)
        self.bundle = self.root / 'bundle.zip'

    def write(self, members=None, duplicate=False):
        with zipfile.ZipFile(self.bundle, 'w') as archive:
            for name, data in (members or self.members).items():
                archive.writestr(name, data)
            if duplicate:
                archive.writestr('README.txt', b'duplicate')
        data = self.bundle.read_bytes()
        return hashlib.sha256(data).hexdigest(), len(data)

    def test_valid_bundle(self):
        digest, size = self.write()
        recovery.verify(self.bundle, self.root, digest, size)

    def test_size_and_whole_hash_fail_closed(self):
        digest, size = self.write()
        for checksum, length in [('0' * 64, size), (digest, size + 1)]:
            with self.assertRaises(ValueError):
                recovery.verify(self.bundle, self.root, checksum, length)

    def test_every_current_input_must_match(self):
        digest, size = self.write()
        for path in [self.root / 'LICENSE', self.root / 'tools/devtools-lock.json',
                     self.root / 'tools/prepare_devtools_sources.py', self.root / 'tools/native/launcher.c']:
            old = path.read_bytes()
            path.write_bytes(old + b' changed')
            with self.assertRaises(ValueError):
                recovery.verify(self.bundle, self.root, digest, size)
            path.write_bytes(old)

    def test_missing_extra_and_duplicate_members_rejected(self):
        missing = dict(self.members); missing.pop('LICENSE')
        extra = dict(self.members); extra['../escape'] = b'bad'
        for members in [missing, extra]:
            digest, size = self.write(members)
            with self.assertRaises(ValueError):
                recovery.verify(self.bundle, self.root, digest, size)
        digest, size = self.write(duplicate=True)
        with self.assertRaises(ValueError):
            recovery.verify(self.bundle, self.root, digest, size)

    def test_internal_hash_and_manifest_checked_even_with_matching_whole_hash(self):
        bad_source = dict(self.members)
        name = next(n for n in bad_source if n.startswith('archives/'))
        bad_source[name] = b'altered source'
        bad_manifest = dict(self.members); bad_manifest['SOURCES.json'] = b'[]'
        for members in [bad_source, bad_manifest]:
            digest, size = self.write(members)
            with self.assertRaises(ValueError):
                recovery.verify(self.bundle, self.root, digest, size)

    def test_new_native_file_invalidates_old_bundle(self):
        digest, size = self.write()
        (self.root / 'tools/native/new.c').write_text('new')
        with self.assertRaises(ValueError):
            recovery.verify(self.bundle, self.root, digest, size)

    def test_restore_atomic_publication_and_cleanup(self):
        digest, size = self.write()
        data = self.bundle.read_bytes()
        class Response(io.BytesIO):
            def geturl(self): return 'https://release-assets.githubusercontent.com/fixture'
        with patch.object(recovery, 'SHA256', digest), patch.object(recovery, 'SIZE', size), \
             patch.object(recovery.urllib.request, 'urlopen', return_value=Response(data)):
            result = recovery.restore(self.root)
        self.assertEqual(result.read_bytes(), data)
        for damaged in [data[:-1], data + b'x', b'x' * size]:
            with patch.object(recovery, 'SHA256', digest), patch.object(recovery, 'SIZE', size), \
                 patch.object(recovery.urllib.request, 'urlopen', return_value=Response(damaged)):
                with self.assertRaises(ValueError): recovery.restore(self.root)
            self.assertEqual(result.read_bytes(), data)
            self.assertFalse(result.with_suffix('.recovery-partial').exists())

    def test_records_include_overrides_and_legacy_sources(self):
        record = {'url': 'https://example.test/a', 'sha256': 'a' * 64}
        lock = {'recipeArchive': record, 'packages': [dict(name='legacy', sourceArchive=record['url'], sourceSha256=record['sha256'])]}
        self.assertEqual([r['name'] for r in recovery.records_for(lock)], ['termux-build-recipes', 'legacy-0'])

    def test_original_collector_output_passes_verifier(self):
        spec = importlib.util.spec_from_file_location('original_collector', Path(__file__).parents[1] / 'tools/prepare_devtools_sources.py')
        collector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(collector)
        def fetch(record):
            path = self.root / record['sha256']
            path.write_bytes(self.members['archives/' + record['sha256']])
            return path
        with patch.object(collector, 'ROOT', self.root), patch.object(collector, 'CACHE', self.root / 'cache'), patch.object(collector, 'fetch', side_effect=fetch):
            bundle = collector.prepare()
        data = bundle.read_bytes()
        recovery.verify(bundle, self.root, hashlib.sha256(data).hexdigest(), len(data))

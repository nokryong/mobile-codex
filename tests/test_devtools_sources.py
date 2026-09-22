import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('devtools_sources', Path(__file__).parents[1] / 'tools/prepare_devtools_sources.py')
sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sources)


class CorrespondingSourcesTests(unittest.TestCase):
    def test_package_recipe_override_and_upstream_patches_are_bundled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'tools/native').mkdir(parents=True)
            record = lambda n: {'url': 'https://example.test/' + n, 'sha256': n * 64}
            lock = {'recipeArchive': record('a'), 'packages': [{
                'name': 'readline', 'recipeArchive': record('b'),
                'sources': [record('c'), record('d')],
            }]}
            (root / 'tools/devtools-lock.json').write_text(json.dumps(lock))
            for name in ['prepare_devtools.py', 'prepare_devtools_sources.py', 'build_native.py', 'requirements-devtools.txt']:
                (root / 'tools' / name).write_text('fixture')
            def fetch(item):
                path = root / item['sha256']
                path.write_text(item['url'])
                return path
            with patch.object(sources, 'ROOT', root), patch.object(sources, 'CACHE', root / 'cache'), patch.object(sources, 'fetch', side_effect=fetch):
                output = sources.prepare()
            with zipfile.ZipFile(output) as archive:
                records = json.loads(archive.read('SOURCES.json'))
                self.assertEqual({r['name'] for r in records}, {
                    'termux-build-recipes', 'readline-build-recipes', 'readline-0', 'readline-1',
                })
                for item in records:
                    self.assertEqual(archive.read(item['archive']).decode(), item['url'])

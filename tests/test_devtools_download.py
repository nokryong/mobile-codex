import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('download_devtools', Path(__file__).parents[1] / 'tools' / 'prepare_devtools.py')
devtools = importlib.util.module_from_spec(spec)
spec.loader.exec_module(devtools)

class PinnedDownloadTests(unittest.TestCase):
    def test_missing_primary_uses_mirror_only_for_identical_pinned_bytes(self):
        data = b'pinned bytes'
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'input.deb'
            url = devtools.POOL_URL + 'pool/input.deb'
            with patch.object(devtools.urllib.request, 'urlopen', side_effect=[urllib.error.HTTPError(url, 404, 'missing', {}, None), io.BytesIO(data)]) as request:
                devtools.download(url, dest, hashlib.sha256(data).hexdigest())
                self.assertEqual(dest.read_bytes(), data)
                self.assertIn('ro.mirror.flokinet.net', request.call_args_list[1].args[0])
    def test_wrong_mirror_bytes_are_never_cached(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'input.deb'
            url = devtools.POOL_URL + 'pool/input.deb'
            with patch.object(devtools.urllib.request, 'urlopen', side_effect=[urllib.error.HTTPError(url, 404, 'missing', {}, None), io.BytesIO(b'wrong')]):
                with self.assertRaises(devtools.PackagingError):
                    devtools.download(url, dest, hashlib.sha256(b'right').hexdigest())
            self.assertFalse(dest.exists())
            self.assertFalse(dest.with_suffix('.deb.partial').exists())

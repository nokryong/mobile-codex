import base64
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('runtime', Path(__file__).parents[1] / 'tools/prepare_runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

class PackagingTests(unittest.TestCase):
    def test_archive_integrity_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'runtime.tgz'
            path.write_bytes(b'approved archive')
            integrity = 'sha512-' + base64.b64encode(hashlib.sha512(path.read_bytes()).digest()).decode()
            runtime.verify(path, integrity)
            path.write_bytes(b'changed archive')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                runtime.verify(path, integrity)

    def test_host_patch_preserves_offsets_and_refuses_new_layouts(self):
        original = b'ELFprefix\0codex-code-mode-host\0padding\0codex-code-mode-host\0suffix'
        patched = runtime.patch_host_name(original)
        self.assertEqual(len(original), len(patched))
        self.assertEqual(original, patched.replace(b'libcodexmodehostx.so', b'codex-code-mode-host'))
        with self.assertRaises(ValueError):
            runtime.patch_host_name(b'new unknown binary')

if __name__ == '__main__':
    unittest.main()

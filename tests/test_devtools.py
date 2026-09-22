import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location(
    "prepare_devtools", Path(__file__).parents[1] / "tools" / "prepare_devtools.py"
)
devtools = importlib.util.module_from_spec(spec)
spec.loader.exec_module(devtools)


class DevtoolsPackagingTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("cc"), "requires Linux ELF compiler")
    def test_versioned_dependency_relocation_preserves_abi_and_executes(self):
        lief = devtools.require_lief()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dep.c").write_text("int answer(void) { return 42; }\n")
            (root / "dep.map").write_text("PCRE2_FIXTURE_1 { global: answer; local: *; };\n")
            (root / "main.c").write_text("extern int answer(void); int main(void) { return answer() == 42 ? 0 : 1; }\n")
            original = root / "libpcre2-8.so"
            subprocess.run(["cc", "-shared", "-fPIC", "dep.c", "-Wl,-soname,libpcre2-8.so",
                            "-Wl,--version-script=dep.map", "-o", str(original)], cwd=root, check=True, capture_output=True)
            executable = root / "git-fixture"
            subprocess.run(["cc", "main.c", "-L.", "-Wl,-rpath,$ORIGIN", "-l:libpcre2-8.so",
                            "-o", str(executable)], cwd=root, check=True, capture_output=True)
            renamed = "libdep_fixture.so"
            mapping = {"libpcre2-8.so": renamed}
            before = lief.parse(str(executable))
            # Reproduce the old packager: DT_NEEDED changed but vn_file unchanged.
            before.remove_library("libpcre2-8.so"); before.add_library(renamed)
            broken = root / "broken"
            before.write(str(broken))
            with self.assertRaisesRegex(devtools.PackagingError, "libpcre2-8.so"):
                devtools.verify_version_requirements(lief.parse(str(broken)), "git-fixture")
            versions = lambda b: sorted((r.name, tuple((a.name, a.hash, a.other) for a in r.get_auxiliary_symbols()))
                                        for r in b.symbols_version_requirement)
            expected = sorted((mapping.get(n, n), aux) for n, aux in versions(lief.parse(str(executable))))
            output = root / "libgit_fixture.so"
            devtools.rewrite_elf(original, root / renamed, mapping, renamed)
            devtools.rewrite_elf(executable, output, mapping, output.name)
            self.assertEqual(expected, versions(lief.parse(str(output))))
            original.unlink(); executable.unlink()
            output.chmod(0o755)
            env = dict(os.environ); env.pop("LD_LIBRARY_PATH", None); env.pop("LD_PRELOAD", None)
            subprocess.run([str(output)], env=env, cwd=root, check=True, capture_output=True, timeout=10)

    def test_dependency_closure_prefers_requested_node_lts_alternative(self):
        index = {
            "npm": {"Depends": "nodejs | nodejs-lts"},
            "nodejs": {"Depends": "libone"},
            "nodejs-lts": {"Depends": "libtwo"},
            "libone": {},
            "libtwo": {"Depends": "libthree"},
            "libthree": {},
        }
        resolved = devtools.resolve_packages(index, ("npm", "nodejs-lts"))
        self.assertEqual(resolved, ["libthree", "libtwo", "nodejs-lts", "npm"])
        self.assertNotIn("nodejs", resolved)

    def test_archive_validation_rejects_path_traversal_symlink_and_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, attrs, body in (
                ("../escape", 0, b"x"),
                ("link", (0o120777 << 16), b"target"),
                ("native", 0, b"\x7fELF\x02\x01" + b"\0" * 64),
            ):
                archive_path = root / (hashlib.sha256(name.encode()).hexdigest() + ".zip")
                with zipfile.ZipFile(archive_path, "w") as archive:
                    info = zipfile.ZipInfo(name)
                    info.external_attr = attrs
                    archive.writestr(info, body)
                with self.assertRaises(devtools.PackagingError):
                    devtools.validate_payload_archive(archive_path)

    def test_archive_hash_and_native_name_collision_are_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "payload.zip"
            devtools.make_zip({"lib/data": b"approved", "etc/tls/cert.pem": b"certificate"}, path)
            with zipfile.ZipFile(path) as archive:
                self.assertEqual(sorted(archive.namelist()), ["etc/tls/cert.pem", "lib/data"])
            approved = devtools.sha256_file(path)
            self.assertEqual(approved, devtools.sha256_file(path))
            path.write_bytes(path.read_bytes() + b"tampered")
            self.assertNotEqual(approved, devtools.sha256_file(path))

        # Different installed paths with identical ELF contents would produce
        # the same flattened JNI filename. The packager must refuse it rather
        # than silently overwriting one dependency with the other.
        sample = b"identical arm64 elf bytes"
        first = devtools.stable_native_name("lib/a.so", sample, "dependency")
        second = devtools.stable_native_name("lib/b.so", sample, "dependency")
        self.assertEqual(first, second)
        with self.assertRaisesRegex(devtools.PackagingError, "native filename collision"):
            if first == second:
                raise devtools.PackagingError(f"native filename collision: {first}")

    def test_fixed_width_shell_patch_does_not_move_elf_contents(self):
        original = b"before" + devtools.TERMUX_SHELL + b"after"
        patched, count = devtools.patch_shell(original)
        self.assertEqual(count, 1)
        self.assertEqual(len(patched), len(original))
        self.assertIn(devtools.ANDROID_SHELL + b"\0", patched)
        self.assertNotIn(devtools.TERMUX_SHELL, patched)

    @unittest.skipUnless((devtools.OUTPUT_DIR / "manifest.json").exists(), "prepared runtime is not present")
    def test_real_prepared_runtime_contract(self):
        """Inspect generated assets, rather than accepting packager code by itself."""
        manifest = json.loads((devtools.OUTPUT_DIR / "manifest.json").read_text(encoding="utf-8"))
        payload_path = devtools.OUTPUT_DIR / manifest["payload"]["file"]
        with zipfile.ZipFile(payload_path) as payload:
            entries = set(payload.namelist())
            self.assertNotIn("usr", {name.split("/", 1)[0] for name in entries})
            self.assertIn("etc/tls/cert.pem", entries)
            self.assertIn("lib/node_modules/npm/bin/npm-cli.js", entries)
            self.assertIn("lib/node_modules/npm/bin/npx-cli.js", entries)
            self.assertIn("share/licenses/mobile-codex/GPL-2.0.txt", entries)
            for name in entries:
                self.assertIsNone(devtools.elf_info(payload.read(name)), name)

        self.assertEqual(set(manifest["versions"]), {"python", "node", "git", "npm", "pip", "ca-certificates"})
        self.assertEqual(manifest["commands"]["node"]["native"], "libnode.so")
        self.assertEqual(manifest["commands"]["python3"]["native"], "libpython3.so")
        self.assertEqual(manifest["prefixVirtualization"]["requiredNative"], "libmc_exec.so")
        self.assertEqual(manifest["prefixVirtualization"]["activation"]["environment"], "LD_PRELOAD")
        self.assertEqual(manifest["unavoidablePathBlockers"], [])

        available = set(manifest["nativeFiles"]) | set(manifest["appOwnedNative"]) | devtools.SYSTEM_NEEDED
        for name, record in manifest["nativeFiles"].items():
            self.assertTrue((devtools.NATIVE_DIR / name).is_file())
            self.assertTrue(set(record["needed"]).issubset(available), (name, record["needed"]))
            binary = devtools.require_lief().parse(str(devtools.NATIVE_DIR / name))
            devtools.verify_version_requirements(binary, name)
            self.assertEqual(list(binary.libraries), record["needed"])
            for requirement in binary.symbols_version_requirement:
                if requirement.name in manifest["nativeFiles"]:
                    self.assertEqual(requirement.name, manifest["nativeFiles"][requirement.name]["soname"])

        links = manifest["links"]
        def resolve_path(target: str, seen: set[str]) -> str:
            if target in entries:
                return "payload"
            if any(entry.startswith(target + "/") for entry in entries):
                return "payload"
            if target in links:
                self.assertNotIn(target, seen, target)
                return resolve(target, seen)
            # A symlink may point through a directory symlink, such as ICU's
            # `current/Makefile.inc`. Resolve the first linked component and
            # preserve the remaining suffix.
            components = target.split("/")
            for length in range(len(components) - 1, 0, -1):
                prefix, suffix = "/".join(components[:length]), "/".join(components[length:])
                if prefix in links:
                    resolved = resolve(prefix, seen)
                    if resolved != "payload":
                        self.fail(f"non-payload directory symlink: {prefix}")
                    replacement = links[prefix]["path"]
                    return resolve_path(replacement + "/" + suffix, seen | {prefix})
            self.fail(f"link target is absent: {target}")

        def resolve(link: str, seen: set[str] | None = None) -> str:
            seen = (seen or set()) | {link}
            spec = links[link]
            if "native" in spec:
                self.assertTrue((devtools.NATIVE_DIR / spec["native"]).is_file(), link)
                return "native"
            if "system" in spec:
                self.assertEqual(spec["system"], "/system/bin/sh")
                return "system"
            target = spec["path"]
            self.assertNotIn("..", target.split("/"), link)
            self.assertNotIn(target, seen, (link, target))
            return resolve_path(target, seen)
        for link in links:
            resolve(link)


if __name__ == "__main__":
    unittest.main()

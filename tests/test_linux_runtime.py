import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "app/src/main/assets/linux/runtime.py"
MANIFEST = {
    "schema": 1,
    "archiveTopLevel": "archlinux-aarch64",
    "maximumEntries": 20,
    "maximumUncompressedBytes": 1024 * 1024,
}


def add_file(archive, name, body=b"ok"):
    item = tarfile.TarInfo(name)
    item.size = len(body)
    archive.addfile(item, __import__("io").BytesIO(body))


def add_dir(archive, name):
    item = tarfile.TarInfo(name)
    item.type = tarfile.DIRTYPE
    item.mode = 0o755
    archive.addfile(item)


class LinuxRuntimeScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.manifest = self.path / "manifest.json"
        self.manifest.write_text(json.dumps(MANIFEST), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def extract(self, build, cancel=False):
        archive = self.path / "fixture.tar.xz"
        with tarfile.open(archive, "w:xz") as output:
            build(output)
        destination = self.path / "rootfs"
        args = [sys.executable, str(SCRIPT), "extract", "--archive", str(archive), "--destination", str(destination),
                "--manifest", str(self.manifest)]
        if cancel:
            marker = self.path / "cancel"
            marker.write_text("1")
            args += ["--cancel-file", str(marker)]
        return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE), destination

    def test_extracts_realistic_prefix_and_guest_absolute_symlink(self):
        def build(output):
            add_dir(output, "archlinux-aarch64")
            add_dir(output, "archlinux-aarch64/etc")
            add_file(output, "archlinux-aarch64/etc/arch-release", b"\n")
            link = tarfile.TarInfo("archlinux-aarch64/bin")
            link.type = tarfile.SYMTYPE; link.linkname = "/usr/bin"
            output.addfile(link)
        result, destination = self.extract(build)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("\n", (destination / "etc/arch-release").read_text())
        self.assertEqual("/usr/bin", os.readlink(destination / "bin"))

    def test_rejects_traversal_and_symlink_parent(self):
        def traversal(output):
            add_dir(output, "archlinux-aarch64")
            add_file(output, "archlinux-aarch64/../escape", b"bad")
        result, destination = self.extract(traversal)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(destination.exists())

        def symlink_parent(output):
            add_dir(output, "archlinux-aarch64")
            link = tarfile.TarInfo("archlinux-aarch64/etc")
            link.type = tarfile.SYMTYPE; link.linkname = "/outside"
            output.addfile(link)
            add_file(output, "archlinux-aarch64/etc/passwd", b"bad")
        result, destination = self.extract(symlink_parent)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(destination.exists())

    def test_rejects_hardlink_and_cancellation_cleans_staging_root(self):
        def hardlink(output):
            add_dir(output, "archlinux-aarch64")
            link = tarfile.TarInfo("archlinux-aarch64/passwd")
            link.type = tarfile.LNKTYPE; link.linkname = "archlinux-aarch64/missing"
            output.addfile(link)
        result, destination = self.extract(hardlink)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(destination.exists())

        result, destination = self.extract(lambda output: add_dir(output, "archlinux-aarch64"), cancel=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(destination.exists())

    def test_guest_environment_and_argv_do_not_inherit_host_runtime(self):
        spec = importlib.util.spec_from_file_location("linux_runtime", SCRIPT)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        environment = module.guest_environment()
        self.assertNotIn("LD_PRELOAD", environment)
        self.assertNotIn("PYTHONHOME", environment)
        root = self.path / "root"; workspace = self.path / "workspace"
        root.mkdir(); workspace.mkdir()
        proot = self.path / "proot"; loader = self.path / "loader"
        for executable in (proot, loader):
            executable.write_bytes(b"fixture")
            executable.chmod(0o700)
        command = ["echo", "literal;$(must-not-expand)", "--", "two words"]
        argv = module.proot_argv(str(root), str(workspace), str(proot), str(loader), command)
        self.assertEqual(command, argv[-len(command):])
        self.assertEqual(["-w", "/workspace"], argv[-len(command) - 2:-len(command)])
        self.assertNotIn("--", argv[:-len(command)])
        self.assertIn(str(workspace) + ":/workspace", argv)
        self.assertIn("--kill-on-exit", argv)
        with self.assertRaises(RuntimeError):
            module.proot_argv("/missing", "/missing", "/missing", "/missing", ["echo", "x;$(bad)"])
        for invalid_command in ([], [""], ["--"], ["-b", "/unexpected"]):
            with self.subTest(command=invalid_command), self.assertRaisesRegex(RuntimeError, "executable"):
                module.proot_argv(str(root), str(workspace), str(proot), str(loader), invalid_command)

    def test_wrapper_status_requires_matching_ready_marker_and_run_drops_delimiter(self):
        spec = importlib.util.spec_from_file_location("linux_runtime", SCRIPT)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        home = self.path / "home"; root = home / "rootfs"; root.mkdir(parents=True)
        (home / "manifest.json").write_text(json.dumps({"id": "pinned"}), encoding="utf-8")
        (home / "state.json").write_text(json.dumps({"enabled": True, "rootfs": str(root)}), encoding="utf-8")
        self.assertEqual((str(root), False, False), module.wrapper_state(str(home)))
        (root / ".mobile-codex-ready").write_text("pinned", encoding="utf-8")
        self.assertEqual((str(root), True, True), module.wrapper_state(str(home)))
        captured = []
        original = module.invoke
        module.invoke = lambda args: captured.append(args.command)
        previous = os.environ.get("MC_LINUX_HOME")
        os.environ["MC_LINUX_HOME"] = str(home)
        try:
            module.wrapper(["--", "echo", "literal;$(no-expand)"])
        finally:
            module.invoke = original
            if previous is None:
                os.environ.pop("MC_LINUX_HOME")
            else:
                os.environ["MC_LINUX_HOME"] = previous
        self.assertEqual([["echo", "literal;$(no-expand)"]], captured)

    def test_invoke_execs_the_requested_proot_binary(self):
        spec = importlib.util.spec_from_file_location("linux_runtime", SCRIPT)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        root = self.path / "root"; workspace = self.path / "workspace"; runtime_home = self.path / "runtime"
        root.mkdir(); workspace.mkdir(); runtime_home.mkdir()
        proot = self.path / "proot"; loader = self.path / "loader"
        for executable in (proot, loader):
            executable.write_bytes(b"fixture"); executable.chmod(0o700)
        captured = []
        original = module.os.execve
        def fake_execve(path, argv, environment):
            captured.extend((path, argv, environment))
            raise RuntimeError("exec intercepted")
        module.os.execve = fake_execve
        args = type("Args", (), {"root": str(root), "workspace": str(workspace), "proot": str(proot),
            "loader": str(loader), "runtime_home": str(runtime_home), "command": ["--", "echo", "ok"]})()
        try:
            with self.assertRaisesRegex(RuntimeError, "exec intercepted"):
                module.invoke(args)
        finally:
            module.os.execve = original
        self.assertEqual(str(proot), captured[0])
        self.assertEqual(str(proot), captured[1][0])
        self.assertEqual(["echo", "ok"], captured[1][-2:])
        self.assertNotIn("--", captured[1])

    @unittest.skipUnless(os.path.isfile(os.environ.get("MC_LINUX_PROOT", ""))
                         and os.path.isfile(os.environ.get("MC_LINUX_LOADER", ""))
                         and Path("/system/bin/linker64").exists(),
                         "requires the installed Android PRoot binaries")
    def test_installed_android_proot_accepts_launcher_command_boundary(self):
        # Exercise the actual packaged parser, loader and exec path. Use an
        # app-owned executable so this does not install or enable a guest OS.
        proot = os.environ["MC_LINUX_PROOT"]
        loader = os.environ["MC_LINUX_LOADER"]
        result = subprocess.run([
            sys.executable, str(SCRIPT), "run", "--root", "/", "--workspace", str(self.path),
            "--proot", proot, "--loader", loader, "--runtime-home", str(self.path),
            "--", proot, "--version",
        ], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        version = next(package["version"] for package in
                       json.loads((ROOT / "tools/devtools-lock.json").read_text())["packages"]
                       if package["name"] == "proot")
        self.assertIn(version, result.stdout)


if __name__ == "__main__":
    unittest.main()

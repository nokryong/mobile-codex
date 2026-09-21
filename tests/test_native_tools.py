import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).parents[1]
spec = importlib.util.spec_from_file_location("build_native", ROOT / "tools" / "build_native.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeBuildTests(unittest.TestCase):
    def test_android_compiler_path_is_arm64_api29(self):
        with tempfile.TemporaryDirectory() as root:
            ndk = Path(root)
            suffix = ".cmd" if native.sys.platform.startswith("win") else ""
            cc = ndk / "toolchains" / "llvm" / "prebuilt" / native.host_tag() / "bin" / f"aarch64-linux-android29-clang{suffix}"
            cc.parent.mkdir(parents=True)
            cc.write_text("")
            self.assertEqual(native.compiler(ndk), cc)

    def test_build_commands_make_pie_launcher_and_16k_safe_preload(self):
        with tempfile.TemporaryDirectory() as root:
            ndk = Path(root)
            suffix = ".cmd" if native.sys.platform.startswith("win") else ""
            cc = ndk / "toolchains" / "llvm" / "prebuilt" / native.host_tag() / "bin" / f"aarch64-linux-android29-clang{suffix}"
            cc.parent.mkdir(parents=True)
            cc.write_text("")
            commands = native.commands(ndk, Path(root) / "out")
        self.assertIn("-pie", commands[0])
        self.assertIn("libmc_launch.so", " ".join(commands[0]))
        self.assertIn("-shared", commands[1])
        self.assertIn("libmc_exec.so", " ".join(commands[1]))
        self.assertIn(str(ROOT / "tools" / "native" / "mc_paths.c"), commands[1])
        for command in commands:
            self.assertIn("-Wl,-z,max-page-size=16384", command)
            self.assertIn("-Wl,-z,common-page-size=16384", command)

    def test_launcher_accepts_only_packaged_aliases(self):
        source = (ROOT / "tools" / "native" / "mc_launch.c").read_text()
        for alias in ('"node"', '"nodejs"', '"python"', '"python3"', '"npm"', '"npx"', '"pip"', '"pip3"'):
            self.assertIn(alias, source)
        self.assertIn("unsupported runtime alias", source)
        self.assertIn("lib/node_modules/npm/bin/npm-cli.js", source)
        self.assertIn("lib/node_modules/npm/bin/npx-cli.js", source)

    def test_preload_only_maps_known_script_interpreters(self):
        source = (ROOT / "tools" / "native" / "mc_exec.c").read_text()
        self.assertIn('"/system/bin/sh"', source)
        self.assertIn('"bin/node"', source)
        self.assertIn('"bin/python3"', source)
        self.assertIn('make_env_replacement', source)
        self.assertIn('env_words', source)
        self.assertIn('interpreter_args', source)
        self.assertIn('owned_args', source)
        self.assertIn('MC_EXEC_TEST_CLOBBER', source)
        self.assertIn('usable_path', source)
        self.assertIn('return false;', source)
        paths = (ROOT / "tools" / "native" / "mc_paths.c").read_text()
        self.assertIn('/data/data/com.termux/files/usr', paths)
        self.assertIn('TMPDIR', paths)
        for hook in ("openat(", "fopen64(", "ONE_PATH_INT(lstat", "opendir(", "dlopen("):
            self.assertIn(hook, paths)
        for hook in ("execve(", "execvpe(", "posix_spawn(", "posix_spawnp("):
            self.assertIn(hook, source)

    @unittest.skipIf(sys.platform.startswith("win"), "host hook execution requires a POSIX dynamic linker")
    def test_preload_hook_preserves_shebang_args_and_handles_path_and_spawn(self):
        cc = shutil.which("cc")
        if cc is None:
            self.skipTest("C compiler unavailable")
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            preload = root / "libmc_exec.so"
            harness = root / "hook-harness"
            prefix = root / "usr"
            (prefix / "bin").mkdir(parents=True)
            os.symlink("/bin/echo", prefix / "bin" / "node")
            os.symlink("/bin/echo", prefix / "bin" / "python3")
            source = root / "hook-harness.c"
            source.write_text(textwrap.dedent("""
                #define _GNU_SOURCE
                #include <errno.h>
                #include <dirent.h>
                #include <dlfcn.h>
                #include <fcntl.h>
                #include <spawn.h>
                #include <stdio.h>
                #include <string.h>
                #include <sys/stat.h>
                #include <sys/wait.h>
                #include <unistd.h>
                extern char **environ;
                int main(int argc, char **argv) {
                  char *args[] = { argv[2], "extra", NULL }; int rc, status; pid_t pid;
                  if (!strcmp(argv[1], "execve")) { execve(argv[2], args, environ); return errno; }
                  if (!strcmp(argv[1], "execvp")) { execvp(argv[2], args); return errno; }
                  if (!strcmp(argv[1], "shell")) {
                    char *shell_args[] = { "/bin/sh", "-c", "exit 41", NULL };
                    execve("/bin/sh", shell_args, environ); return errno;
                  }
                  if (!strcmp(argv[1], "paths")) {
                    char read_buffer[8], link_buffer[32]; struct stat st; DIR *dir; FILE *tmp; int fd;
                    fd = open(argv[2], O_CREAT | O_TRUNC | O_WRONLY, 0600); if (fd < 0 || write(fd, "ok", 2) != 2) return 21; close(fd);
                    fd = openat(AT_FDCWD, argv[2], O_RDONLY); if (fd < 0 || read(fd, read_buffer, 2) != 2 || memcmp(read_buffer, "ok", 2)) return 22; close(fd);
                    if (stat(argv[2], &st) || lstat(argv[6], &st) || access(argv[2], R_OK)) return 23;
                    dir = opendir(argv[5]); if (!dir) return 24; closedir(dir);
                    if (readlink(argv[6], link_buffer, sizeof(link_buffer)) < 0) return 25;
                    if (mkdir(argv[7], 0700) && errno != EEXIST) return 26;
                    tmp = fopen(argv[3], "w"); if (!tmp || fputs("tmp", tmp) == EOF || fclose(tmp)) return 27;
                    fd = open(argv[4], O_CREAT | O_TRUNC | O_WRONLY, 0600); if (fd < 0) return 28; close(fd);
                    if (rename(argv[2], argv[8]) || unlink(argv[8])) return 29;
                    if (!dlopen(argv[9], RTLD_NOW)) return 30;
                    return 0;
                  }
                  if (!strcmp(argv[1], "spawnp")) {
                    rc = posix_spawnp(&pid, argv[2], NULL, NULL, args, environ);
                    if (rc) return rc; waitpid(pid, &status, 0); return WIFEXITED(status) ? WEXITSTATUS(status) : 127;
                  }
                  return 125;
                }
            """))
            preload_command = [cc, "-shared", "-fPIC", "-DMC_EXEC_TEST_CLOBBER", "-o", str(preload), str(ROOT / "tools/native/mc_exec.c"), str(ROOT / "tools/native/mc_paths.c"), "-ldl"]
            subprocess.run(preload_command, check=True)
            subprocess.run([cc, "-o", str(harness), str(source)], check=True)
            env = os.environ | {"LD_PRELOAD": str(preload), "MC_PREFIX": str(prefix), "MC_NATIVE_DIR": str(root), "PATH": str(root)}

            node_script = root / "node-tool"
            node_script.write_text("#!/usr/bin/env -S node --trace-warnings\nconsole.log('body-is-not-an-argument');\n")
            python_script = root / "python-tool"
            python_script.write_text("#!/usr/bin/python3 -u\r\nprint('body-is-not-an-argument')\n")
            actual_python_script = root / "actual-python-tool"
            actual_python_script.write_text(f"#!{Path(sys.executable).resolve()}\nprint('actual-python-shebang')\n")
            fake_python = root / "libtool_python_fake.so"
            os.symlink(Path(sys.executable).resolve(), fake_python)
            fake_python_script = root / "fake-python-tool"
            fake_python_script.write_text(f"#!{fake_python}\nprint('fake-python-shebang')\n")
            path_script = root / "path-tool"
            path_script.write_text("#!/usr/bin/env node\n")

            node = subprocess.run([harness, "execve", node_script], env=env, text=True, capture_output=True)
            self.assertEqual(node.returncode, 0, node.stderr)
            self.assertEqual(node.stdout.strip(), f"--trace-warnings {node_script} extra")
            python = subprocess.run([harness, "execve", python_script], env=env, text=True, capture_output=True)
            self.assertEqual(python.returncode, 0, python.stderr)
            self.assertEqual(python.stdout.strip(), f"-u {python_script} extra")
            actual_python_env = env | {"MC_PYTHON": str(Path(sys.executable).resolve())}
            actual_python = subprocess.run([harness, "execve", actual_python_script], env=actual_python_env, text=True, capture_output=True)
            self.assertEqual(actual_python.returncode, 0, actual_python.stderr)
            self.assertEqual(actual_python.stdout.strip(), "actual-python-shebang")
            fake_python_env = env | {"MC_PYTHON": str(fake_python)}
            fake_python_result = subprocess.run([harness, "execve", fake_python_script], env=fake_python_env, text=True, capture_output=True)
            self.assertEqual(fake_python_result.returncode, 0, fake_python_result.stderr)
            self.assertEqual(fake_python_result.stdout.strip(), "fake-python-shebang")
            path = subprocess.run([harness, "execvp", "path-tool"], env=env, text=True, capture_output=True)
            self.assertEqual(path.returncode, 0, path.stderr)
            self.assertEqual(path.stdout.strip(), f"{path_script} extra")
            spawned = subprocess.run([harness, "spawnp", "path-tool"], env=env, text=True, capture_output=True)
            self.assertEqual(spawned.returncode, 0, spawned.stderr)
            self.assertEqual(spawned.stdout.strip(), f"{path_script} extra")
            # Linux CI has no Android system shell: ENOENT proves /bin/sh was rewritten.
            shell = subprocess.run([harness, "shell", "unused"], env=env, text=True, capture_output=True)
            self.assertEqual(shell.returncode, 2, shell.stderr)

            unsupported = root / "unsupported-env"
            unsupported.write_text("#!/usr/bin/env -i node\n")
            rejected = subprocess.run([harness, "execve", unsupported], env=env, text=True, capture_output=True)
            self.assertNotEqual(rejected.returncode, 0, "env -i must not lose its environment through rewriting")

            legacy = "/data/data/com.termux/files/usr"
            (prefix / "etc").mkdir()
            (prefix / "lib").mkdir()
            temp_root = root / "temporary"; temp_root.mkdir()
            os.symlink("target", prefix / "etc" / "link")
            probe_source = root / "probe.c"; probe_source.write_text("int mc_probe(void) { return 7; }\n")
            subprocess.run([cc, "-shared", "-fPIC", "-o", str(prefix / "lib" / "libprobe.so"), str(probe_source)], check=True)
            path_env = env | {"TMPDIR": str(temp_root)}
            path_run = subprocess.run([harness, "paths", legacy + "/etc/runtime", legacy + "/tmp/sem.test", str(root / "outside"),
                                       legacy + "/etc", legacy + "/etc/link", legacy + "/etc/newdir", legacy + "/etc/renamed",
                                       legacy + "/lib/libprobe.so"], env=path_env, text=True, capture_output=True)
            self.assertEqual(path_run.returncode, 0, path_run.stderr)
            self.assertTrue((temp_root / "sem.test").is_file())
            self.assertTrue((root / "outside").is_file())
            self.assertTrue((prefix / "etc" / "newdir").is_dir())
            self.assertFalse((prefix / "etc" / "runtime").exists())
            self.assertFalse((prefix / "etc" / "renamed").exists())


if __name__ == "__main__":
    unittest.main()

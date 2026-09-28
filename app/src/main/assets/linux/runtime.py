#!/usr/bin/env python3
"""Small, dependency-free Linux rootfs installer and PRoot launcher.

This script runs with Mobile Codex's bundled Python.  It deliberately owns no
networking: Java downloads and verifies the pinned artifact before extraction.
"""
import argparse
import json
import os
import shutil
from types import SimpleNamespace
import sys
import tarfile


def fail(message):
    raise RuntimeError(message)


def emit(**value):
    print(json.dumps(value, separators=(",", ":")), flush=True)


def read_manifest(path):
    with open(path, "r", encoding="utf-8") as handle:
        value = json.load(handle)
    needed = ("schema", "archiveTopLevel", "maximumEntries", "maximumUncompressedBytes")
    if value.get("schema") != 1 or any(key not in value for key in needed):
        fail("invalid Linux runtime manifest")
    return value


def cancelled(path):
    return bool(path and os.path.exists(path))


def member_path(name, top):
    # Tar and the Android host both use POSIX paths. A backslash can appear in
    # legitimate systemd unit names; only slash separates path components.
    if not name or "\x00" in name or name.startswith("/"):
        fail("unsafe archive path")
    bits = name.split("/")
    if any(bit in ("", ".", "..") for bit in bits):
        fail("unsafe archive path")
    if bits[0] != top:
        fail("unexpected archive top-level directory")
    if len(bits) == 1:
        return ()
    return tuple(bits[1:])


def safe_relative_link(parent, target):
    if "\x00" in target:
        return False
    if target.startswith("/"):
        # Absolute targets are guest paths. They are written as link text and
        # never resolved against Android's filesystem.
        return True
    parts = list(parent)
    for bit in target.split("/"):
        if bit in ("", "."):
            continue
        if bit == "..":
            if not parts:
                return False
            parts.pop()
        else:
            parts.append(bit)
    return True


def path_for(root, parts):
    return os.path.join(root, *parts)


def ensure_parents(root, parts, kinds, directory_modes):
    current = []
    for part in parts:
        current.append(part)
        key = tuple(current)
        if kinds.get(key) == "symlink":
            fail("archive entry has a symlink parent")
        target = path_for(root, key)
        if os.path.lexists(target):
            if os.path.islink(target) or not os.path.isdir(target):
                fail("archive entry parent is not a directory")
        else:
            os.mkdir(target, 0o700)
            kinds[key] = "directory"
            directory_modes[key] = 0o755


def extract(args):
    manifest = read_manifest(args.manifest)
    root = os.path.abspath(args.destination)
    if os.path.exists(root):
        fail("extraction destination already exists")
    os.makedirs(root, mode=0o700)
    kinds = {(): "directory"}
    directory_modes = {}
    explicit = set()
    entries = 0
    total = 0
    next_report = 8 * 1024 * 1024
    try:
        with tarfile.open(args.archive, "r:xz") as archive:
            for member in archive:
                if cancelled(args.cancel_file):
                    fail("cancelled")
                entries += 1
                if entries > int(manifest["maximumEntries"]):
                    fail("archive has too many entries")
                parts = member_path(member.name, manifest["archiveTopLevel"])
                if not parts:
                    if not member.isdir():
                        fail("archive top-level is not a directory")
                    continue
                if parts in explicit:
                    fail("duplicate archive entry")
                # GNU tar can omit parent directory headers until after a child.
                # Accept that one explicit directory record, but never permit a
                # second entry or a type collision at the same path.
                if parts in kinds:
                    if not member.isdir() or kinds[parts] != "directory":
                        fail("archive entry type collision")
                    directory_modes[parts] = (member.mode & 0o777) or 0o755
                    explicit.add(parts)
                    continue
                if os.path.lexists(path_for(root, parts)):
                    fail("duplicate archive entry")
                ensure_parents(root, parts[:-1], kinds, directory_modes)
                target = path_for(root, parts)
                mode = member.mode & 0o777
                if member.isdir():
                    # Directories may be read-only in a valid rootfs. Keep them
                    # writable while later archive entries are installed, then
                    # restore their exact safe mode after extraction.
                    os.mkdir(target, 0o700)
                    kinds[parts] = "directory"
                    directory_modes[parts] = mode or 0o755
                elif member.isfile():
                    total += member.size
                    if total > int(manifest["maximumUncompressedBytes"]):
                        fail("archive expands beyond configured limit")
                    source = archive.extractfile(member)
                    if source is None:
                        fail("cannot read archive entry")
                    # O_EXCL keeps archive collisions from becoming writes through
                    # a filesystem race or a pre-existing symlink.
                    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode or 0o644)
                    try:
                        with os.fdopen(descriptor, "wb") as output:
                            while True:
                                if cancelled(args.cancel_file):
                                    fail("cancelled")
                                block = source.read(1024 * 1024)
                                if not block:
                                    break
                                output.write(block)
                    finally:
                        source.close()
                    os.chmod(target, mode or 0o644)
                    kinds[parts] = "file"
                elif member.issym():
                    if not safe_relative_link(parts[:-1], member.linkname):
                        fail("unsafe archive symlink")
                    os.symlink(member.linkname, target)
                    kinds[parts] = "symlink"
                elif member.islnk():
                    fail("hard links are not accepted in Linux runtime archives")
                else:
                    fail("special archive entry is not accepted")
                explicit.add(parts)
                if total >= next_report:
                    emit(event="extracting", entries=entries, extractedBytes=total)
                    next_report = total + 8 * 1024 * 1024
    except Exception:
        # The Java caller also cleans staging on process failure. This makes the
        # standalone tool safe and keeps failed roots from looking usable.
        shutil.rmtree(root, ignore_errors=True)
        raise
    # Android does not expose its resolver configuration as a stable bindable
    # file. A regular guest file avoids a dangling systemd-resolved link while
    # retaining the rootfs's bundled CA store for TLS clients.
    if kinds.get(("etc",)) != "directory":
        fail("rootfs etc path is not a directory")
    resolv = os.path.join(root, "etc", "resolv.conf")
    if os.path.lexists(resolv):
        os.unlink(resolv)
    with open(resolv, "w", encoding="ascii") as output:
        output.write("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    os.chmod(resolv, 0o644)
    for parts in sorted(directory_modes, key=len, reverse=True):
        os.chmod(path_for(root, parts), directory_modes[parts])
    emit(event="extracted", entries=entries, extractedBytes=total)


def guest_environment():
    # Do not pass Android runtime variables into glibc. In particular the host
    # preload and Python configuration can make an otherwise valid guest fail.
    return {
        "HOME": "/root",
        "TMPDIR": "/tmp",
        "LANG": "C.UTF-8",
        "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "SSL_CERT_FILE": "/etc/ssl/certs/ca-certificates.crt",
    }


def proot_argv(root, workspace, proot, loader, command):
    # PRoot ends option parsing at the first command; it does not accept a
    # standalone "--". Reject option-looking executables while preserving all
    # following guest arguments, including their own "--" separators.
    if not command or not command[0] or command[0].startswith("-"):
        fail("guest command must start with an executable path or name")
    if not os.path.isdir(root):
        fail("Linux rootfs is unavailable")
    if not os.path.isdir(workspace):
        fail("selected workspace is unavailable")
    if ":" in workspace:
        fail("PRoot cannot bind a workspace path containing a colon")
    if not (os.path.isfile(proot) and os.access(proot, os.X_OK)):
        fail("PRoot executable is unavailable")
    if not (os.path.isfile(loader) and os.access(loader, os.X_OK)):
        fail("PRoot loader is unavailable")
    # /dev and /proc are kernel pseudo-filesystems. No host home, Android app
    # files, credentials, or generic storage directories are mounted here.
    return [proot, "--kill-on-exit", "-0", "-r", root, "-b", workspace + ":/workspace", "-b", "/dev", "-b", "/proc", "-w", "/workspace"] + command


def invoke(args, smoke=False):
    command = ["/bin/sh", "-lc", "test -r /etc/arch-release && /bin/true"] if smoke else args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        fail("missing guest command")
    argv = proot_argv(args.root, args.workspace, args.proot, args.loader, command)
    env = guest_environment()
    env["PROOT_LOADER"] = args.loader
    env["PROOT_TMP_DIR"] = os.path.join(args.runtime_home, "tmp")
    env["PROOT_DONT_POLLUTE_ROOTFS"] = "1"
    os.makedirs(env["PROOT_TMP_DIR"], mode=0o700, exist_ok=True)
    # Replace this wrapper for both smoke and normal commands. This gives Java
    # one PRoot process to stop, rather than leaving a Python parent behind.
    os.execve(args.proot, argv, env)


def wrapper_state(home):
    try:
        with open(os.path.join(home, "state.json"), "r", encoding="utf-8") as handle:
            value = json.load(handle)
        root = value.get("rootfs", os.path.join(home, "rootfs"))
        with open(os.path.join(home, "manifest.json"), "r", encoding="utf-8") as handle:
            manifest = json.load(handle)
        with open(os.path.join(root, ".mobile-codex-ready"), "r", encoding="utf-8") as handle:
            ready = handle.read(129) == manifest.get("id")
        return root, bool(value.get("enabled")) and ready, ready
    except (OSError, ValueError, TypeError):
        return os.path.join(home, "rootfs"), False, False


def status(args):
    _, enabled, ready = wrapper_state(args.home)
    print(json.dumps({"available": ready, "enabled": enabled}, separators=(",", ":")))


def wrapper(argv):
    """Entry point used by the tiny native mc-linux alias.

    The alias forwards literal arguments only. It cannot select or download a
    runtime; readiness and enablement are checked again here before PRoot.
    """
    home = os.environ.get("MC_LINUX_HOME", "")
    if argv in (["status"], ["--status"]):
        status(SimpleNamespace(home=home))
        return
    if not argv or argv[0] != "--":
        fail("usage: mc-linux --status | mc-linux -- <command> [args...]")
    root, enabled, ready = wrapper_state(home)
    if not enabled or not ready:
        fail("Linux runtime is disabled")
    invoke(SimpleNamespace(root=root, workspace=os.getcwd(), proot=os.environ.get("MC_LINUX_PROOT", ""),
                           loader=os.environ.get("MC_LINUX_LOADER", ""), runtime_home=home, command=argv[1:]))


def main():
    # Native aliases call this compact form. Keep the explicit subcommands for
    # the Java installer and for deterministic tests.
    if len(sys.argv) > 1 and (sys.argv[1] in ("--", "--status") or (sys.argv[1] == "status" and len(sys.argv) == 2)):
        wrapper(sys.argv[1:])
        return
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="operation", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--root", required=True)
    common.add_argument("--workspace", required=True)
    common.add_argument("--proot", required=True)
    common.add_argument("--loader", required=True)
    common.add_argument("--runtime-home", required=True)
    extraction = sub.add_parser("extract")
    extraction.add_argument("--archive", required=True)
    extraction.add_argument("--destination", required=True)
    extraction.add_argument("--manifest", required=True)
    extraction.add_argument("--cancel-file", default="")
    smoke = sub.add_parser("smoke", parents=[common])
    run = sub.add_parser("run", parents=[common])
    run.add_argument("command", nargs=argparse.REMAINDER)
    state = sub.add_parser("status")
    state.add_argument("--home", required=True)
    args = parser.parse_args()
    if args.operation == "extract":
        extract(args)
    elif args.operation == "smoke":
        invoke(args, smoke=True)
    elif args.operation == "run":
        invoke(args)
    else:
        status(args)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)

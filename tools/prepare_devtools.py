#!/usr/bin/env python3
"""Build the relocatable Termux developer-tool runtime for Android arm64.

The input is the Termux *aarch64* APT index, frozen in ``devtools-lock.json``.
Only packages named in the lock are fetched, and every downloaded ``.deb`` is
verified before it is read.  The resulting archive contains data only; all
ELFs are renamed and put in ``jniLibs/arm64-v8a`` where Android can execute
them through the app's native-library directory.

This is deliberately not an APT client.  It accepts no maintainer scripts,
does not invoke dpkg, and never follows links while unpacking a package.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import posixpath
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "tools" / "devtools-lock.json"
OUTPUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "devtools"
NATIVE_DIR = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
CACHE_DIR = ROOT / ".runtime-cache" / "termux-debs"
INDEX_URL = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages"
POOL_URL = "https://packages.termux.dev/apt/termux-main/"
GPL2_URL = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt"
GPL2_SHA256 = "edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6"
TERMUX_PREFIX = b"/data/data/com.termux/files/usr"
TERMUX_SHELL = TERMUX_PREFIX + b"/bin/sh"
ANDROID_SHELL = b"/system/bin/sh"
TARGETS = ("python", "nodejs-lts", "git", "npm", "python-pip", "ca-certificates")
SYSTEM_NEEDED = {
    # Bionic and Android platform libraries are supplied by the device, not
    # copied from Termux.  libc++ is intentionally excluded: the APK already
    # owns libc++_shared.so and a mismatch must fail packaging.
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libandroid.so",
    "libz.so", "libGLESv2.so", "libEGL.so", "libOpenSLES.so",
}
SAFE_MEMBER = re.compile(r"^[A-Za-z0-9._+@%/=-]+$")


class PackagingError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path, expected_sha256: str | None = None) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and (not expected_sha256 or sha256_file(destination) == expected_sha256):
        return
    partial = destination.with_suffix(destination.suffix + ".partial")
    partial.unlink(missing_ok=True)
    try:
        with urllib.request.urlopen(url, timeout=180) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        if expected_sha256 and sha256_file(partial) != expected_sha256:
            raise PackagingError(f"SHA-256 mismatch for {url}")
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


def parse_control(text: str) -> dict[str, dict[str, str]]:
    """Parse Debian control paragraphs without accepting malformed continuations."""
    packages: dict[str, dict[str, str]] = {}
    for paragraph in text.replace("\r\n", "\n").strip().split("\n\n"):
        record: dict[str, str] = {}
        field: str | None = None
        for line in paragraph.splitlines():
            if line.startswith((" ", "\t")):
                if field is None:
                    raise PackagingError("orphan Debian control continuation")
                record[field] += "\n" + line[1:]
            elif ": " in line:
                field, value = line.split(": ", 1)
                record[field] = value
            else:
                raise PackagingError(f"malformed Debian control line: {line!r}")
        name = record.get("Package")
        if not name:
            raise PackagingError("absent package name in Packages")
        if name in packages:
            # The official stable index may retain an older package revision.
            # It is ordered newest-first.  Freeze the first concrete record;
            # the generated lock then eliminates this ambiguity permanently.
            if record.get("Version") == packages[name].get("Version"):
                raise PackagingError(f"duplicate package version in Packages: {name!r}")
            continue
        packages[name] = record
    return packages


def dependency_alternatives(expression: str) -> list[list[str]]:
    """Return package-name alternatives, leaving Debian version checks to APT's lock.

    Package metadata in the frozen index has already selected one concrete
    version per name.  We retain package alternatives so a declared target
    (notably nodejs-lts) wins over the first arbitrary APT alternative.
    """
    result: list[list[str]] = []
    for group in filter(None, (part.strip() for part in expression.split(","))):
        names: list[str] = []
        for alternative in group.split("|"):
            match = re.match(r"\s*([A-Za-z0-9.+-]+)", alternative)
            if match:
                names.append(match.group(1))
        if not names:
            raise PackagingError(f"cannot parse dependency: {group!r}")
        result.append(names)
    return result


def resolve_packages(index: dict[str, dict[str, str]], targets: Iterable[str] = TARGETS) -> list[str]:
    selected = set(targets)
    missing = selected - set(index)
    if missing:
        raise PackagingError(f"requested packages absent from aarch64 index: {sorted(missing)}")
    pending = list(selected)
    while pending:
        name = pending.pop()
        record = index[name]
        for field in ("Pre-Depends", "Depends"):
            for alternatives in dependency_alternatives(record.get(field, "")):
                # Prefer an explicitly selected package. This makes npm's
                # `nodejs | nodejs-lts` resolve to nodejs-lts.
                choice = next((x for x in alternatives if x in selected), None)
                choice = choice or next((x for x in alternatives if x in index), None)
                if choice is None:
                    raise PackagingError(f"{name}: no satisfiable dependency {alternatives}")
                if choice not in selected:
                    selected.add(choice)
                    pending.append(choice)
    return sorted(selected)


def recipe_metadata(package: str, record: dict[str, str], recipe_commit: str) -> dict[str, str]:
    # Termux main packages are built from these recipe paths.  A recipe URL is
    # provenance, not a claim that the moving recipe commit reproduces every
    # upstream source bit used by the Termux build service.
    # A few Termux recipes emit more than one binary package. Keep their
    # actual source recipe rather than inventing a non-existent path.
    recipe_package = {"ncurses-ui-libs": "ncurses"}.get(package, package)
    recipe = f"https://github.com/termux/termux-packages/blob/{recipe_commit}/packages/{recipe_package}/build.sh"
    try:
        with urllib.request.urlopen(recipe.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/"), timeout=60) as response:
            text = response.read().decode("utf-8")
    except OSError as error:
        raise PackagingError(f"cannot fetch pinned Termux recipe for {package}") from error

    def scalar(variable: str) -> str:
        match = re.search(rf"^{variable}=(?:\"([^\"]*)\"|'([^']*)'|([^\s#(]+))\s*$", text, re.MULTILINE)
        return next((value for value in match.groups() if value is not None), "") if match else ""

    def array(variable: str) -> list[str]:
        match = re.search(rf"^{variable}=\(", text, re.MULTILINE)
        if not match:
            value = scalar(variable)
            return [value] if value else []
        start, depth, quote, escaped = match.end(), 1, None, False
        end = None
        for offset, character in enumerate(text[start:], start):
            if quote:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == quote:
                    quote = None
                continue
            if character in "'\"":
                quote = character
            elif character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0:
                    end = offset
                    break
        if end is None:
            raise PackagingError(f"unclosed recipe array: {variable}")
        # shlex only tokenizes literals; it neither expands variables nor
        # executes substitutions. Dynamic shell expressions remain visible as
        # tokens and are deliberately omitted below.
        try:
            return shlex.split(text[start:end], comments=True, posix=True)
        except ValueError:
            return ["$(unresolved)"]

    variables = {key: scalar(key) for key in re.findall(r"^(_[A-Z0-9_]+)=", text, re.MULTILINE)}
    version_parts = array("TERMUX_PKG_VERSION")
    if not version_parts:
        version_parts = [scalar("TERMUX_PKG_VERSION")]
    for position, value in enumerate(version_parts):
        for key, replacement in variables.items():
            value = value.replace(f"${{{key}}}", replacement).replace(f"${key}", replacement)
        version_parts[position] = value

    def expand(value: str) -> str | None:
        if not value or "$(" in value or "$((" in value:
            return None
        for index, part in enumerate(version_parts):
            value = value.replace(f"${{TERMUX_PKG_VERSION[{index}]}}", part)
        value = value.replace("${TERMUX_PKG_VERSION//./_}", version_parts[0].replace(".", "_"))
        value = value.replace("${TERMUX_PKG_VERSION:2}", version_parts[0][2:])
        value = value.replace("${TERMUX_PKG_VERSION}", version_parts[0]).replace("$TERMUX_PKG_VERSION", version_parts[0])
        for key, replacement in variables.items():
            value = value.replace(f"${{{key}}}", replacement).replace(f"${key}", replacement)
        return value if "$" not in value else None

    urls, hashes = array("TERMUX_PKG_SRCURL"), array("TERMUX_PKG_SHA256")
    sources = []
    unresolved = 0
    for index, url in enumerate(urls):
        url = expand(url)
        checksum = hashes[index] if index < len(hashes) else ""
        if url and re.fullmatch(r"[0-9a-f]{64}", checksum) and (url.startswith("https://") or url.startswith("git+https://")):
            sources.append({"url": url, "sha256": checksum})
        else:
            unresolved += 1
    # Recipes with a small, explicit shell transformation are pinned here as
    # literal archive URLs. This is parsing, never execution of recipe code.
    if package == "ca-certificates":
        sources = [{"url": f"https://curl.se/ca/cacert-{record['Version'].split(':', 1)[-1].replace('.', '-')}.pem", "sha256": hashes[0]}]
        unresolved = 0
    if package == "libsqlite":
        major, minor, patch = record["Version"].split("-")[0].split(".")
        version_digits = f"{int(major)}{int(minor):02d}{int(patch):02d}00"
        sources = [{"url": f"https://www.sqlite.org/{variables['_SQLITE_YEAR']}/sqlite-src-{version_digits}.zip", "sha256": hashes[0]}]
        unresolved = 0
    if package == "npm" and record["Version"] == "11.19.1":
        # Termux fetches the upstream git tag. Distribute a content-addressed
        # codeload snapshot, which the source-bundle tool can download without
        # executing git or the package recipe.
        sources = [{"url": "https://codeload.github.com/npm/cli/tar.gz/refs/tags/v11.19.1", "sha256": "e2e5cf2209565ec98c8602bbe2bd6002fa55e0af45cd903eb3869955678030ba"}]
        unresolved = 0
    if package in {"ncurses", "ncurses-ui-libs"}:
        # The first two entries are parsed above; three further terminfo
        # sources are delegated to pinned x11 recipes.
        sources.extend(ncurses_nested_sources(recipe_commit))
        unresolved = 0
    binary_version = record["Version"].split(":", 1)[-1].rsplit("-", 1)[0]
    recipe_version_matches = bool(version_parts and version_parts[0].split(":", 1)[-1] == binary_version)
    return {
        "homepage": scalar("TERMUX_PKG_HOMEPAGE") or record.get("Homepage", ""),
        "license": scalar("TERMUX_PKG_LICENSE"),
        "recipe": recipe,
        "recipeVersion": version_parts[0] if version_parts else "",
        "recipeVersionMatches": recipe_version_matches,
        "sources": sources,
        "sourceArchiveStatus": "local" if not urls and not sources else ("complete" if not unresolved else "partial"),
    }


def ncurses_nested_sources(recipe_commit: str) -> list[dict[str, str]]:
    """Read the three x11 recipe literals used by ncurses terminfo.

    The ncurses build recipe delegates these values through shell command
    substitution. We read only simple assignment values from the pinned raw
    recipe files and expand the declared package version ourselves; no recipe
    shell code is evaluated.
    """
    records: list[dict[str, str]] = []
    for name in ("kitty", "alacritty", "foot"):
        url = f"https://raw.githubusercontent.com/termux/termux-packages/{recipe_commit}/x11-packages/{name}/build.sh"
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                text = response.read().decode("utf-8")
        except OSError as error:
            raise PackagingError(f"cannot fetch pinned x11 recipe for ncurses: {name}") from error
        def literal(variable: str) -> str:
            match = re.search(rf"^{variable}=(?:\"([^\"]*)\"|'([^']*)'|([^\s#(]+))\s*$", text, re.MULTILINE)
            return next((value for value in match.groups() if value is not None), "") if match else ""
        version, source, checksum = literal("TERMUX_PKG_VERSION"), literal("TERMUX_PKG_SRCURL"), literal("TERMUX_PKG_SHA256")
        source = source.replace("${TERMUX_PKG_VERSION}", version).replace("$TERMUX_PKG_VERSION", version)
        if not version or not source.startswith("https://") or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise PackagingError(f"unresolved pinned x11 source recipe: {name}")
        records.append({"url": source, "sha256": checksum})
    return records


def git_head(url: str = "https://github.com/termux/termux-packages.git") -> str:
    try:
        output = subprocess.check_output(["git", "ls-remote", url, "HEAD"], text=True, timeout=30)
        commit = output.split()[0]
    except (OSError, subprocess.SubprocessError, IndexError) as error:
        raise PackagingError("cannot pin Termux build-recipe commit; pass --recipe-commit") from error
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise PackagingError("unexpected termux-packages git HEAD")
    return commit


def write_lock(index_path: Path | None, recipe_commit: str | None) -> dict[str, Any]:
    cache_index = index_path or (ROOT / ".runtime-cache" / "termux-Packages")
    download(INDEX_URL, cache_index)
    raw_index = cache_index.read_bytes()
    index = parse_control(raw_index.decode("utf-8"))
    recipe_commit = recipe_commit or git_head()
    recipe_archive_url = f"https://codeload.github.com/termux/termux-packages/tar.gz/{recipe_commit}"
    recipe_archive_path = ROOT / ".runtime-cache" / f"termux-packages-{recipe_commit}.tar.gz"
    download(recipe_archive_url, recipe_archive_path)
    names = resolve_packages(index)
    lock_packages = []
    for name in names:
        record = index[name]
        if record.get("Architecture") not in ("aarch64", "all"):
            raise PackagingError(f"{name} is not usable on arm64: {record.get('Architecture')}")
        filename = record.get("Filename", "")
        checksum = record.get("SHA256", "")
        if not filename.startswith("pool/") or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise PackagingError(f"{name} has unsafe or unpinned archive metadata")
        lock_packages.append({
            "name": name, "version": record["Version"], "architecture": record["Architecture"],
            "filename": filename, "sha256": checksum, "size": int(record.get("Size", "0")),
            "depends": record.get("Depends", ""), **recipe_metadata(name, record, recipe_commit),
        })
    lock = {
        "schema": 1,
        "architecture": "aarch64",
        "targets": list(TARGETS),
        "index": {"url": INDEX_URL, "sha256": sha256_bytes(raw_index)},
        "poolUrl": POOL_URL,
        "termuxPackagesRecipeCommit": recipe_commit,
        "recipeArchive": {
            "url": recipe_archive_url,
            "sha256": sha256_file(recipe_archive_path),
        },
        "packages": lock_packages,
        "buildRecipe": "tools/prepare_devtools.py --write-lock && tools/prepare_devtools.py",
    }
    LOCK_PATH.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return lock


def read_lock() -> dict[str, Any]:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock.get("schema") != 1 or lock.get("architecture") != "aarch64":
        raise PackagingError("unsupported devtools lock format")
    if not isinstance(lock.get("packages"), list) or not lock["packages"]:
        raise PackagingError("lock has no package records")
    for package in lock["packages"]:
        if not re.fullmatch(r"[0-9a-f]{64}", package.get("sha256", "")):
            raise PackagingError(f"un-pinned package in lock: {package.get('name')}")
        if not package.get("filename", "").startswith("pool/"):
            raise PackagingError(f"unsafe filename in lock: {package.get('name')}")
    return lock


def ar_members(blob: bytes) -> dict[str, bytes]:
    if not blob.startswith(b"!<arch>\n"):
        raise PackagingError("not a Debian ar archive")
    result: dict[str, bytes] = {}
    offset = 8
    while offset < len(blob):
        header = blob[offset:offset + 60]
        if len(header) != 60 or header[58:60] != b"`\n":
            raise PackagingError("malformed ar member")
        raw_name = header[:16].decode("ascii", "strict").strip()
        if not raw_name.endswith("/") or raw_name.startswith(("/", "..")):
            raise PackagingError(f"unsafe ar member name: {raw_name!r}")
        name = raw_name[:-1]
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError as error:
            raise PackagingError("invalid ar member size") from error
        start, end = offset + 60, offset + 60 + size
        if end > len(blob) or name in result:
            raise PackagingError("truncated or duplicate ar member")
        result[name] = blob[start:end]
        offset = end + (size & 1)
    return result


def deb_data_tar(deb: Path) -> tuple[str, bytes]:
    members = ar_members(deb.read_bytes())
    data_members = [(name, data) for name, data in members.items() if name.startswith("data.tar.")]
    if len(data_members) != 1:
        raise PackagingError(f"{deb.name}: expected exactly one data.tar member")
    return data_members[0]


def open_tar(name: str, data: bytes) -> tarfile.TarFile:
    # Python 3.14 supports xz/gz/bz2/zstd where provided.  Termux currently
    # uses zstd; spell each supported format out so an unknown compressor fails.
    suffixes = {"data.tar.xz": "r:xz", "data.tar.gz": "r:gz", "data.tar.zst": "r:zst", "data.tar.bz2": "r:bz2"}
    try:
        return tarfile.open(fileobj=io.BytesIO(data), mode=suffixes[name])
    except (KeyError, tarfile.TarError, ValueError) as error:
        raise PackagingError(f"cannot read {name}") from error


def safe_tar_members(archive: tarfile.TarFile) -> Iterable[tarfile.TarInfo]:
    for member in archive:
        name = member.name.replace("\\", "/")
        normalized = posixpath.normpath(name)
        if member.isdir() and normalized in (".", ""):
            yield member
            continue
        if not SAFE_MEMBER.fullmatch(name) or normalized in (".", "") or normalized.startswith("../") or name.startswith("/"):
            raise PackagingError(f"unsafe tar member: {member.name!r}")
        if member.islnk() or member.isdev() or member.isfifo():
            raise PackagingError(f"unsupported tar member type: {member.name!r}")
        if member.issym():
            link = member.linkname.replace("\\", "/")
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(normalized), link))
            if link.startswith("/") or resolved.startswith("../"):
                raise PackagingError(f"unsafe tar symlink: {member.name!r}")
        elif not (member.isfile() or member.isdir()):
            raise PackagingError(f"unsupported tar member type: {member.name!r}")
        yield member


def elf_info(data: bytes) -> dict[str, Any] | None:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    if data[4] != 2 or data[5] != 1:
        raise PackagingError("ELF must be 64-bit little-endian")
    machine, typ = struct.unpack_from("<HH", data, 18)
    if machine != 183:
        raise PackagingError(f"non-ARM64 ELF machine {machine}")
    phoff, = struct.unpack_from("<Q", data, 32)
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    has_interp = False
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + 4 > len(data):
            raise PackagingError("truncated ELF program headers")
        program_type, = struct.unpack_from("<I", data, offset)
        has_interp |= program_type == 3  # PT_INTERP
    return {"type": typ, "hasInterp": has_interp}


def is_prefix_path(path: str) -> bool:
    return path.startswith("data/data/com.termux/files/usr/")


def relative_prefix_path(path: str) -> str:
    path = path.removeprefix("./")
    if not is_prefix_path(path):
        raise PackagingError(f"package contains data outside Termux usr: {path}")
    return path.removeprefix("data/data/com.termux/files/usr/")


def elf_kind(relative_path: str, info: dict[str, Any]) -> str:
    if info["hasInterp"] or relative_path.startswith("bin/") or relative_path.startswith("libexec/"):
        return "tool"
    if "/site-packages/" in relative_path or "/lib-dynload/" in relative_path:
        return "python-module"
    return "dependency"


def stable_native_name(relative_path: str, data: bytes, kind: str) -> str:
    digest = sha256_bytes(data)[:16]
    if relative_path == "bin/node":
        return "libnode.so"
    if relative_path in {"bin/python", "bin/python3", "bin/python3.14"}:
        return "libpython3.so"
    prefix = {"tool": "libtool", "python-module": "libpy", "dependency": "libdep"}[kind]
    return f"{prefix}_{digest}.so"


def patch_shell(data: bytes) -> tuple[bytes, int]:
    """Patch only the known fixed-width Termux shell string.

    The trailing NUL fill makes the replacement exactly the original length;
    no ELF offsets, sections, or relocation data move.
    """
    replacement = ANDROID_SHELL + b"\0" * (len(TERMUX_SHELL) - len(ANDROID_SHELL))
    if len(replacement) != len(TERMUX_SHELL):
        raise AssertionError("shell patch must preserve byte length")
    return data.replace(TERMUX_SHELL, replacement), data.count(TERMUX_SHELL)


def termux_prefix_reference_count(data: bytes) -> int:
    return data.count(TERMUX_PREFIX)


def require_lief():
    try:
        import lief  # type: ignore
    except ImportError as error:
        raise PackagingError("LIEF is required: python -m pip install lief") from error
    return lief


def lief_soname(binary: Any, lief: Any) -> str | None:
    for entry in binary.dynamic_entries:
        if getattr(entry, "tag", None) == lief.ELF.DynamicEntry.TAG.SONAME:
            return getattr(entry, "name", None)
    return None


def rewrite_elf(source: Path, destination: Path, rename_needed: dict[str, str], native_name: str) -> tuple[str | None, list[str]]:
    """Rewrite DT_NEEDED, RUNPATH/RPATH and SONAME after flattening into JNI libs."""
    lief = require_lief()
    binary = lief.parse(str(source))
    if binary is None or not isinstance(binary, lief.ELF.Binary):
        raise PackagingError(f"LIEF cannot parse {source}")
    original_libraries = list(binary.libraries)
    for needed in original_libraries:
        replacement = rename_needed.get(needed)
        if replacement and replacement != needed:
            binary.remove_library(needed)
            binary.add_library(replacement)
    # There is one Android native-library directory, so old Termux runpaths
    # would point at an inaccessible prefix. Remove them rather than retaining
    # a plausible-looking but wrong path.
    for entry in list(binary.dynamic_entries):
        if isinstance(entry, (lief.ELF.DynamicEntryRunPath, lief.ELF.DynamicEntryRpath)):
            if isinstance(entry, lief.ELF.DynamicEntryRunPath):
                entry.runpath = "$ORIGIN"
            else:
                entry.rpath = "$ORIGIN"
        if isinstance(entry, lief.ELF.DynamicEntryLibrary) and entry.name == native_name:
            # SONAME entries are DynamicEntryLibrary on some LIEF releases;
            # do not infer that a needed library is a SONAME.
            pass
    # LIEF exposes SONAME as a named dynamic entry in current releases.
    for entry in binary.dynamic_entries:
        if getattr(entry, "tag", None) == lief.ELF.DynamicEntry.TAG.SONAME:
            entry.name = native_name
    destination.parent.mkdir(parents=True, exist_ok=True)
    binary.write(str(destination))
    parsed = lief.parse(str(destination))
    if parsed is None:
        raise PackagingError(f"LIEF failed to write {destination}")
    return lief_soname(parsed, lief), list(parsed.libraries)


def make_zip(entries: dict[str, bytes], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            if not name or name.startswith("/") or ".." in name.split("/") or name.endswith("/"):
                raise PackagingError(f"unsafe payload archive path: {name}")
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.external_attr = (0o644 & 0xFFFF) << 16
            archive.writestr(info, entries[name])


def validate_payload_archive(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            if not name or name.startswith("/") or ".." in name.split("/") or info.is_dir():
                raise PackagingError(f"unsafe payload zip entry: {name!r}")
            # Unix symlinks use this high-file-type mask. The Java installer
            # creates manifest links itself; zip must never supply one.
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise PackagingError(f"payload zip may not contain symlinks: {name!r}")
            data = archive.read(info)
            if elf_info(data) is not None:
                raise PackagingError(f"payload zip contains executable ELF: {name!r}")


def command_aliases(git_native: str) -> dict[str, dict[str, str]]:
    launcher = {name: {"native": "", "launcher": "libmc_launch.so"} for name in (
        "node", "nodejs", "python", "python3", "python3.14", "npm", "npx", "pip", "pip3", "pip3.14", "git",
    )}
    launcher["npm"].update({"script": "lib/node_modules/npm/bin/npm-cli.js", "native": "libnode.so"})
    launcher["npx"].update({"script": "lib/node_modules/npm/bin/npx-cli.js", "native": "libnode.so"})
    launcher["node"].update({"native": "libnode.so"})
    launcher["nodejs"].update({"native": "libnode.so"})
    launcher["python"].update({"native": "libpython3.so"})
    launcher["python3"].update({"native": "libpython3.so"})
    launcher["python3.14"].update({"native": "libpython3.so"})
    for pip in ("pip", "pip3", "pip3.14"):
        launcher[pip].update({"native": "libpython3.so"})
    launcher["git"] = {"native": git_native}
    launcher["sh"] = {"system": "/system/bin/sh"}
    return launcher


def prefix_link_resolves(target: str, files: dict[str, Any], symlinks: dict[str, str], native_paths: dict[str, str], seen: set[str] | None = None) -> bool:
    """Resolve a relative prefix link, including a symlinked directory."""
    seen = seen or set()
    if target in seen:
        return False
    if target in files or target in native_paths:
        return True
    if any(path.startswith(target + "/") for path in files) or any(path.startswith(target + "/") for path in native_paths):
        return True
    if target in symlinks:
        return prefix_link_resolves(symlinks[target], files, symlinks, native_paths, seen | {target})
    parts = target.split("/")
    for count in range(len(parts) - 1, 0, -1):
        prefix = "/".join(parts[:count])
        if prefix in symlinks:
            replacement = symlinks[prefix]
            suffix = "/".join(parts[count:])
            return prefix_link_resolves(replacement + "/" + suffix, files, symlinks, native_paths, seen | {target})
    return False


def license_notice_payload(lock: dict[str, Any]) -> tuple[dict[str, bytes], list[dict[str, str]]]:
    """Create a compact provenance notice and carry Git's full GPLv2 text.

    The separately distributed corresponding-source bundle contains all
    upstream license files. The APK itself still needs the complete GPL text
    for the Git executable rather than a bare SPDX identifier.
    """
    lines = ["Mobile Codex bundled Termux developer tools", "", "Package provenance:"]
    for package in lock["packages"]:
        lines.append(f"- {package['name']} {package['version']}: {package.get('license') or 'license in corresponding source'}")
        lines.append(f"  recipe: {package['recipe']}")
        for source in package.get("sources", []):
            lines.append(f"  source: {source['url']} SHA-256 {source['sha256']}")
    gpl = ROOT / ".runtime-cache" / "GPL-2.0.txt"
    download(GPL2_URL, gpl, GPL2_SHA256)
    return ({
        "share/licenses/mobile-codex/TERMUX-THIRD-PARTY-NOTICES.txt": ("\n".join(lines) + "\n").encode("utf-8"),
        "share/licenses/mobile-codex/GPL-2.0.txt": gpl.read_bytes(),
    }, [{"path": "share/licenses/mobile-codex/GPL-2.0.txt", "sha256": GPL2_SHA256, "url": GPL2_URL}])


def relocation_overrides(source: str) -> list[str] | None:
    """Configuration variables needed in addition to libmc_exec path translation."""
    if source == "bin/node":
        return ["NODE_PATH", "NODE_EXTRA_CA_CERTS"]
    if source.startswith("bin/python") or source.startswith("lib/python") or source == "lib/libpython3.14.so":
        return ["PYTHONHOME", "PYTHONUSERBASE", "PIP_USER", "SSL_CERT_FILE"]
    if source == "bin/git" or source.startswith("libexec/git-core/"):
        return ["GIT_EXEC_PATH", "GIT_SSL_CAINFO"]
    if source in {"lib/libssl.so.3", "lib/libcrypto.so.3", "lib/libcurl.so"}:
        return ["OPENSSL_CONF", "SSL_CERT_FILE", "GIT_SSL_CAINFO", "PIP_CERT", "NODE_EXTRA_CA_CERTS"]
    if source.startswith(("bin/", "lib/", "libexec/")):
        # These dependency utilities are never exposed as startup commands;
        # libmc_exec translates their compiled paths before the filesystem
        # call, while this list retains only actual configuration variables.
        return []
    return None


def prepare(lock: dict[str, Any] | None = None) -> dict[str, Any]:
    lock = lock or read_lock()
    staging = Path(tempfile.mkdtemp(prefix="mobile-codex-devtools-", dir=ROOT / ".runtime-cache"))
    try:
        files: dict[str, tuple[bytes, str]] = {}
        symlinks: dict[str, str] = {}
        for package in lock["packages"]:
            # Debian filenames may contain an epoch colon, which is illegal on
            # Windows. The archive hash is a safe, unambiguous cache key.
            deb = CACHE_DIR / f"{package['name']}-{package['sha256'][:16]}.deb"
            download(lock["poolUrl"] + package["filename"], deb, package["sha256"])
            name, raw_tar = deb_data_tar(deb)
            with open_tar(name, raw_tar) as archive:
                for member in safe_tar_members(archive):
                    if member.isdir():
                        continue
                    relative = relative_prefix_path(member.name)
                    if member.issym():
                        if relative in files or relative in symlinks:
                            raise PackagingError(f"duplicate installed path: {relative}")
                        symlinks[relative] = posixpath.normpath(posixpath.join(posixpath.dirname(relative), member.linkname))
                        continue
                    stream = archive.extractfile(member)
                    if stream is None:
                        raise PackagingError(f"cannot read tar member: {member.name}")
                    data = stream.read()
                    if relative in files and files[relative][0] != data:
                        raise PackagingError(f"conflicting files from packages: {relative}")
                    files[relative] = (data, package["name"])

        elf_records: list[dict[str, Any]] = []
        path_to_native: dict[str, str] = {}
        termux_cpp_data: bytes | None = None
        # The application already packages one libc++_shared.so for Codex and
        # the launcher. Do not overwrite or rename a second C++ runtime: all
        # Termux ELFs must resolve the app-owned SONAME instead.
        needed_renames: dict[str, str] = {"libc++_shared.so": "libc++_shared.so"}
        for relative, (data, package) in sorted(files.items()):
            info = elf_info(data)
            if info is None:
                continue
            if relative == "lib/libc++_shared.so":
                termux_cpp_data = data
                path_to_native[relative] = "libc++_shared.so"
                continue
            kind = elf_kind(relative, info)
            native = stable_native_name(relative, data, kind)
            if native in path_to_native.values():
                raise PackagingError(f"native filename collision: {native}")
            path_to_native[relative] = native
            # DT_NEEDED references SONAME, generally the original basename.
            needed_renames.setdefault(Path(relative).name, native)
            elf_records.append({"path": relative, "package": package, "kind": kind, "native": native, "data": data})

        # DT_NEEDED identifies shared objects by DT_SONAME, which often is a
        # versioned name different from the package's installed filename (for
        # example libz.so.1). Build the mapping from the real original ELF.
        lief = require_lief()
        existing_cpp = NATIVE_DIR / "libc++_shared.so"
        if not existing_cpp.exists() or elf_info(existing_cpp.read_bytes()) is None:
            raise PackagingError("APK must provide an ARM64 app-owned libc++_shared.so before packaging devtools")
        if termux_cpp_data is None:
            raise PackagingError("selected Termux libc++ package did not provide libc++_shared.so")
        termux_cpp = lief.parse(list(termux_cpp_data))
        app_cpp = lief.parse(str(existing_cpp))
        if termux_cpp is None or app_cpp is None:
            raise PackagingError("LIEF cannot inspect libc++ ABI compatibility")
        termux_cpp_exports = {symbol.name for symbol in termux_cpp.dynamic_symbols if symbol.exported and symbol.name}
        app_cpp_exports = {symbol.name for symbol in app_cpp.dynamic_symbols if symbol.exported and symbol.name}
        cpp_required: set[str] = set()
        original_binaries: dict[str, Any] = {}
        for record in elf_records:
            original = staging / "original" / record["native"]
            original.parent.mkdir(parents=True, exist_ok=True)
            original.write_bytes(record["data"])
            binary = lief.parse(str(original))
            original_binaries[record["native"]] = binary
            soname = lief_soname(binary, lief) if binary is not None else ""
            if soname:
                previous = needed_renames.setdefault(soname, record["native"])
                if previous != record["native"]:
                    raise PackagingError(f"ambiguous SONAME {soname}: {previous}, {record['native']}")
        for binary in original_binaries.values():
            if binary is not None and "libc++_shared.so" in binary.libraries:
                cpp_required.update(symbol.name for symbol in binary.dynamic_symbols if symbol.imported and symbol.name in termux_cpp_exports)
        missing_cpp_symbols = sorted(cpp_required - app_cpp_exports)
        if missing_cpp_symbols:
            raise PackagingError(f"app libc++_shared.so lacks Termux-required ABI symbols: {missing_cpp_symbols}")
        # Some Termux shared libraries deliberately omit DT_SONAME. Their
        # package symlink is then the ABI name referenced by consumers.
        for link, target in symlinks.items():
            if target in path_to_native:
                alias = Path(link).name
                previous = needed_renames.setdefault(alias, path_to_native[target])
                if previous != path_to_native[target]:
                    raise PackagingError(f"ambiguous library symlink {alias}: {previous}, {path_to_native[target]}")

        native_manifest: dict[str, dict[str, Any]] = {}
        prefix_references: list[dict[str, Any]] = []
        for record in elf_records:
            patched, shell_count = patch_shell(record.pop("data"))
            source = staging / "source" / record["native"]
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(patched)
            destination = NATIVE_DIR / record["native"]
            soname, libraries = rewrite_elf(source, destination, needed_renames, record["native"])
            # Re-check the generated output rather than trusting LIEF's object.
            output = destination.read_bytes()
            if elf_info(output) is None:
                raise PackagingError(f"LIEF wrote non-ELF {destination.name}")
            refs = termux_prefix_reference_count(output)
            if refs:
                overrides = relocation_overrides(record["path"])
                if overrides is None:
                    raise PackagingError(f"unclassified compiled Termux prefix reference: {record['path']}")
                prefix_references.append({
                    "native": destination.name, "source": record["path"], "count": refs,
                    "pathAdapter": "libmc_exec.so", "requiredEnvironment": overrides,
                })
            native_manifest[destination.name] = {
                "sha256": sha256_file(destination), "kind": record["kind"], "source": record["path"],
                "package": record["package"], "soname": soname, "needed": libraries, "shellPathPatches": shell_count,
            }

        # Verify the app-owned runtime before it is permitted to satisfy the
        # Termux C++ dependency. This is intentionally a hard packaging
        # condition, not a best-effort manifest warning.
        available_needed = set(native_manifest) | {"libc++_shared.so"} | SYSTEM_NEEDED
        unresolved_needed: dict[str, list[str]] = {}
        for native, record in native_manifest.items():
            unresolved = sorted(set(record["needed"]) - available_needed)
            if unresolved:
                unresolved_needed[native] = unresolved
        if unresolved_needed:
            raise PackagingError(f"unresolved ELF dependencies after flattening: {unresolved_needed}")

        payload: dict[str, bytes] = {}
        links: dict[str, dict[str, str]] = {}
        for relative, (data, _) in files.items():
            if relative in path_to_native:
                links[relative] = {"native": path_to_native[relative]}
            else:
                # Zip members are relative to the installed usr prefix. The
                # installer extracts them beneath its versioned `.../usr`.
                payload[relative] = data
        notices, license_notices = license_notice_payload(lock)
        payload.update(notices)
        for relative, target in symlinks.items():
            if target in path_to_native:
                links[relative] = {"native": path_to_native[target]}
            elif prefix_link_resolves(target, files, symlinks, path_to_native):
                links[relative] = {"path": target}
            elif relative.startswith(("bin/", "lib/", "libexec/", "etc/")):
                raise PackagingError(f"runtime symlink has no packaged target: {relative} -> {target}")
            # A few Termux documentation symlinks point to license texts that
            # are not shipped in the binary package. Do not materialize a
            # knowingly dangling link in the installed prefix.
        git_native = path_to_native.get("bin/git")
        if not git_native:
            raise PackagingError("git executable is missing from selected packages")
        commands = command_aliases(git_native)
        for command, spec in commands.items():
            if "native" in spec:
                links[f"bin/{command}"] = {"native": spec.get("launcher", spec["native"])}
            else:
                links[f"bin/{command}"] = {"system": spec["system"]}
        payload_zip = OUTPUT_DIR / "payload.zip"
        make_zip(payload, payload_zip)
        validate_payload_archive(payload_zip)
        required = ["libmc_launch.so", "libmc_exec.so", "libc++_shared.so", *sorted(native_manifest)]
        missing = [name for name in required[:2] if not (NATIVE_DIR / name).exists()]
        manifest = {
            "schema": 1,
            "architecture": "arm64-v8a",
            "versions": {
                "python": next(p["version"] for p in lock["packages"] if p["name"] == "python"),
                "node": next(p["version"] for p in lock["packages"] if p["name"] == "nodejs-lts"),
                "git": next(p["version"] for p in lock["packages"] if p["name"] == "git"),
                "npm": next(p["version"] for p in lock["packages"] if p["name"] == "npm"),
                "pip": next(p["version"] for p in lock["packages"] if p["name"] == "python-pip"),
                "ca-certificates": next(p["version"] for p in lock["packages"] if p["name"] == "ca-certificates"),
            },
            "pythonVersion": next(p["version"] for p in lock["packages"] if p["name"] == "python").split("-")[0],
            "packageIndex": lock["index"], "termuxPackagesRecipeCommit": lock["termuxPackagesRecipeCommit"],
            "packages": lock["packages"],
            "licenseNotices": license_notices,
            "payload": {"file": "payload.zip", "sha256": sha256_file(payload_zip), "size": payload_zip.stat().st_size},
            "nativeFiles": native_manifest, "appOwnedNative": {"libc++_shared.so": {"sha256": sha256_file(existing_cpp), "termuxRequiredSymbols": len(cpp_required)}},
            "links": dict(sorted(links.items())), "commands": commands,
            "requiredNative": required, "missingRequiredNative": missing,
            "termuxPrefixReferences": prefix_references,
            "prefixVirtualization": {
                "requiredNative": "libmc_exec.so",
                "activation": {"environment": "LD_PRELOAD", "value": "<nativeLibraryDir>/libmc_exec.so"},
                "mappings": [
                    {"from": "/data/data/com.termux/files/usr", "toEnvironment": "MC_PREFIX"},
                    {"from": "/data/data/com.termux/files/usr/tmp", "toEnvironment": "TMPDIR"},
                ],
                "note": "MC_PREFIX alone does not redirect compiled Termux paths; libmc_exec.so must be preloaded.",
            },
            "opensslEnvironment": {
                "OPENSSL_CONF": "etc/tls/openssl.cnf",
                "SSL_CERT_FILE": "etc/tls/cert.pem",
                "OPENSSL_ENGINES": "lib/engines-3",
                "OPENSSL_MODULES": "lib/ossl-modules",
                "note": "Set these paths relative to MC_PREFIX so OpenSSL does not use its compiled Termux defaults.",
            },
            "unavoidablePathBlockers": [],
        }
        (OUTPUT_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return manifest
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def verify_output() -> None:
    manifest_path = OUTPUT_DIR / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_payload_archive(OUTPUT_DIR / manifest["payload"]["file"])
    if sha256_file(OUTPUT_DIR / manifest["payload"]["file"]) != manifest["payload"]["sha256"]:
        raise PackagingError("payload zip hash mismatch")
    for name, record in manifest["nativeFiles"].items():
        path = NATIVE_DIR / name
        if not path.exists() or sha256_file(path) != record["sha256"] or elf_info(path.read_bytes()) is None:
            raise PackagingError(f"invalid packaged native file: {name}")
    for name in manifest["requiredNative"]:
        if not (NATIVE_DIR / name).exists():
            raise PackagingError(f"required native library is absent: {name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-lock", action="store_true", help="freeze current official aarch64 Packages metadata")
    parser.add_argument("--index", type=Path, help="local Packages index, for reproducible lock tests")
    parser.add_argument("--recipe-commit", help="pinned termux-packages recipe commit used in lock")
    parser.add_argument("--verify", action="store_true", help="verify an already prepared output")
    args = parser.parse_args()
    if args.write_lock:
        lock = write_lock(args.index, args.recipe_commit)
        print(f"Wrote {LOCK_PATH} with {len(lock['packages'])} packages")
    elif args.verify:
        verify_output()
        print("Devtools runtime verified")
    else:
        manifest = prepare()
        print(f"Prepared {len(manifest['nativeFiles'])} native files and {manifest['payload']['size']} payload bytes")


if __name__ == "__main__":
    main()

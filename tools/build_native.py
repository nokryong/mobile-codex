#!/usr/bin/env python3
"""Build Mobile Codex's ARM64 Android launcher and script compatibility shim.

The executable is deliberately emitted with a ``.so`` name: Android extracts
APK native-library entries under that convention and permits those paths to be
executed, unlike app-private runtime files.  No generated ELF is tracked.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "native"
OUTPUT = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
API = 29


def host_tag() -> str:
    if sys.platform.startswith("win"):
        return "windows-x86_64"
    if sys.platform == "darwin":
        return "darwin-x86_64"
    return "linux-x86_64"


def compiler(ndk: Path) -> Path:
    candidate = ndk / "toolchains" / "llvm" / "prebuilt" / host_tag() / "bin" / f"aarch64-linux-android{API}-clang"
    if sys.platform.startswith("win"):
        candidate = candidate.with_suffix(".cmd")
        if not candidate.exists():
            candidate = candidate.with_suffix(".exe")
    if not candidate.is_file():
        raise FileNotFoundError(
            f"Android NDK compiler not found: {candidate}. Use NDK r28c or pass --ndk / set ANDROID_NDK_HOME."
        )
    return candidate


def commands(ndk: Path, output: Path = OUTPUT) -> list[list[str]]:
    cc = str(compiler(ndk))
    common = [cc, "-O2", "-Wall", "-Wextra", "-Werror", "-D_FORTIFY_SOURCE=2", "-ffunction-sections", "-fdata-sections",
              "-Wl,--gc-sections", "-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384"]
    return [
        common + ["-fPIE", "-pie", "-o", str(output / "libmc_launch.so"), str(SOURCE / "mc_launch.c")],
        common + ["-fPIC", "-shared", "-Wl,-soname,libmc_exec.so", "-o", str(output / "libmc_exec.so"), str(SOURCE / "mc_exec.c"), str(SOURCE / "mc_paths.c"), "-ldl"],
    ]


def build(ndk: Path, output: Path = OUTPUT) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for command in commands(ndk, output):
        subprocess.run(command, check=True)
    print(f"Built ARM64 API {API} native launcher files in {output}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if args.ndk is None:
        parser.error("pass --ndk or set ANDROID_NDK_HOME")
    build(args.ndk.expanduser().resolve(), args.output.resolve())


if __name__ == "__main__":
    main()

"""Restore the Termux payload from the pinned project release on a cold CI build."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
APK_SHA256 = "48f05bd8812a5907a4e80bf8d228afc754875f497c0c380e94a06e1db2ec6731"


def restore(apk):
    with apk.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != APK_SHA256:
            raise ValueError("Unexpected release APK digest")
    lock = json.loads((ROOT / "tools/devtools-lock.json").read_text())
    with ZipFile(apk) as archive:
        assets = ROOT / "app/src/main/assets/devtools"
        libs = ROOT / "app/src/main/jniLibs/arm64-v8a"
        manifest = json.loads(archive.read("assets/devtools/manifest.json"))
        if manifest["packages"] != lock["packages"]:
            raise ValueError("Release packages differ from the checkout's lock")
        names = list(manifest["nativeFiles"])
        if any(not re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so", name) for name in names):
            raise ValueError("Unexpected native library name")
        assets.mkdir(parents=True, exist_ok=True)
        libs.mkdir(parents=True, exist_ok=True)
        for name in names:
            with archive.open("lib/arm64-v8a/" + name) as src, (libs / name).open("wb") as dst:
                shutil.copyfileobj(src, dst)
            (libs / name).chmod(0o755)
        for name in ("manifest.json", "payload.zip"):
            with archive.open("assets/devtools/" + name) as src, (assets / name).open("wb") as dst:
                shutil.copyfileobj(src, dst)


if __name__ == "__main__":
    restore(Path(sys.argv[1]))

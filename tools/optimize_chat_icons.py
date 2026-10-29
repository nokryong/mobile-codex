#!/usr/bin/env python3
"""Derive lossless bundled WebP icons; original character packs are immutable."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'icon-pack/gptchan'
OUTPUT = ROOT / 'app/src/main/assets/web/chat-icons'
PINNED = ROOT / 'tests/chat-icons.sha256.json'
DERIVED = ROOT / 'tests/chat-icons-derived.sha256.json'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(generate=False):
    originals = json.loads(PINNED.read_text())
    if len(originals) != 32:
        raise ValueError('Expected exactly 32 pinned original icons')
    entries = {}
    for name, pinned in originals.items():
        if not re.fullmatch(r'[0-9]{2}-[a-z]+(?:-[a-z]+)*\.png', name):
            raise ValueError('Invalid icon name')
        source, target = SOURCE / name, OUTPUT / name.replace('.png', '.webp')
        if sha(source.read_bytes()) != pinned:
            raise ValueError('Original artwork changed: ' + name)
        with Image.open(source) as original:
            if generate:
                original.save(target, format='WEBP', lossless=True, quality=100, method=4, exact=True)
            with Image.open(target) as derived:
                rgba = original.convert('RGBA').tobytes()
                if original.size != derived.size or rgba != derived.convert('RGBA').tobytes():
                    raise ValueError('Pixel/alpha mismatch: ' + name)
                entries[name] = dict(asset=target.name, sourceSha256=pinned, sha256=sha(target.read_bytes()),
                                     width=original.width, height=original.height, rgbaSha256=sha(rgba))
        if generate:
            duplicate = OUTPUT / name
            if duplicate.exists():
                if sha(duplicate.read_bytes()) != pinned:
                    raise ValueError('Refusing to remove changed bundled original: ' + name)
                duplicate.unlink()  # The verified original remains in icon-pack/gptchan.
    manifest = dict(format='webp-lossless', icons=entries)
    if generate:
        (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        DERIVED.write_text(json.dumps({name: entry['sha256'] for name, entry in entries.items()}, indent=2) + '\n', encoding='utf-8')
    elif json.loads((OUTPUT / 'manifest.json').read_text()) != manifest or json.loads(DERIVED.read_text()) != {name: entry['sha256'] for name, entry in entries.items()}:
        raise ValueError('Bundled manifest or hash fixture mismatch')
    print(json.dumps(dict(icons=len(entries), originalBytes=sum((SOURCE / name).stat().st_size for name in originals),
                         derivedBytes=sum((OUTPUT / entry['asset']).stat().st_size for entry in entries.values()), exactPixels=True)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generate', action='store_true')
    run(parser.parse_args().generate)

#!/usr/bin/env python3
"""Seals the licensed brand assets into brand/assets.bin (AES-256-GCM).

    python3 tools/brand/seal.py <payload-dir>

<payload-dir> holds `font/*.ttf` and `license/*.txt`. The key is read from $TRIMIO_BRAND_KEY or
brand/brand.key (base64, 32 bytes) and created in brand/brand.key when neither exists. The key
must never be committed: keep it in a password manager and in the GitHub secret TRIMIO_BRAND_KEY.

Format: b"TRMB1" | 12-byte nonce | ciphertext+tag of a zip of the payload. The Gradle task
`:core:brand:unsealBrandAssets` reverses it (see core/brand/build.gradle.kts).
"""
import base64
import io
import os
import pathlib
import sys
import zipfile

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = pathlib.Path(__file__).resolve().parents[2]
KEY_FILE = ROOT / "brand" / "brand.key"
OUT = ROOT / "brand" / "assets.bin"
MAGIC = b"TRMB1"


def load_key() -> bytes:
    env = os.environ.get("TRIMIO_BRAND_KEY")
    if env:
        return base64.b64decode(env.strip())
    if KEY_FILE.exists():
        return base64.b64decode(KEY_FILE.read_text().strip())
    key = AESGCM.generate_key(bit_length=256)
    KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    KEY_FILE.write_text(base64.b64encode(key).decode() + "\n")
    KEY_FILE.chmod(0o600)
    print(f"created {KEY_FILE} (do not commit it)")
    return key


def main(payload: pathlib.Path) -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(payload.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(payload).as_posix())
    key = load_key()
    if len(key) != 32:
        sys.exit("the key must be 32 bytes")
    nonce = os.urandom(12)
    OUT.write_bytes(MAGIC + nonce + AESGCM(key).encrypt(nonce, buffer.getvalue(), MAGIC))
    print(f"sealed {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]))

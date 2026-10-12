#!/usr/bin/env python3
"""
Shared code of the content-pack builders (tools/packs/*): the `.tpk` container, the signed
manifest, the channel index, and key handling. Everything here mirrors the app
(engine/models/.../Packs.kt, PackChannel.kt) — change both together.

Container (`.tpk`): b"TPK1", u32 file count, then per file: u16 path length, UTF-8 path, u64 size,
bytes (big-endian). No compression (Lottie/SVG JSON is stored as is; audio is already compressed or
short), relative paths only.

Manifest signature: Ed25519 over the UTF-8 lines
    trimio-pack/1, id, kind, version, size, sha256 (lower-case hex), minApp, license
joined by "\n" (PackManifest.signedBytes()).

Keys: the private key never enters the repository. `keygen` writes a raw 32-byte seed (hex) to
--key (default ~/.config/trimio/pack-signing.key); CI passes it as the secret TRIMIO_PACK_KEY
(hex). Its public key is pinned in the app (engine/models/.../PackChannel.kt, PackKeys.content).
"""
import hashlib
import json
import os
import struct
import sys

FORMAT = "trimio-pack/1"
INDEX_FORMAT = "trimio-packs/1"
DEFAULT_KEY = os.path.expanduser("~/.config/trimio/pack-signing.key")
KEY_ID = "packs-2026-1"
KINDS = {"Style", "Sound", "Music", "Model", "Art", "Overlay", "Look"}


def safe(name):
    return bool(name) and not name.startswith("/") and "\\" not in name and ":" not in name and all(
        p not in ("", ".", "..") for p in name.split("/"))


def write_tpk(files, out_path):
    """files: list of (relative path, bytes or source file path). Streams to out_path; returns (size, sha256)."""
    h = hashlib.sha256()
    size = 0
    with open(out_path, "wb") as out:
        def put(b):
            nonlocal size
            out.write(b)
            h.update(b)
            size += len(b)
        put(b"TPK1")
        put(struct.pack(">I", len(files)))
        for name, data in files:
            if not safe(name):
                raise ValueError(f"unsafe path {name}")
            n = name.encode("utf-8")
            put(struct.pack(">H", len(n)))
            put(n)
            if isinstance(data, (bytes, bytearray)):
                put(struct.pack(">Q", len(data)))
                put(bytes(data))
            else:
                put(struct.pack(">Q", os.path.getsize(data)))
                with open(data, "rb") as f:
                    while True:
                        chunk = f.read(1 << 20)
                        if not chunk:
                            break
                        put(chunk)
    return size, h.hexdigest()


def read_tpk(path):
    """Yields (name, bytes) — for checks and tests."""
    with open(path, "rb") as f:
        if f.read(4) != b"TPK1":
            raise ValueError("not a pack")
        (count,) = struct.unpack(">I", f.read(4))
        for _ in range(count):
            (ln,) = struct.unpack(">H", f.read(2))
            name = f.read(ln).decode("utf-8")
            (size,) = struct.unpack(">Q", f.read(8))
            yield name, f.read(size)


def load_key(path=None):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    env = os.environ.get("TRIMIO_PACK_KEY", "").strip()
    seed = bytes.fromhex(env) if env else None
    if seed is None:
        with open(path or DEFAULT_KEY, "r", encoding="ascii") as f:
            seed = bytes.fromhex(f.read().strip())
    return Ed25519PrivateKey.from_private_bytes(seed)


def public_hex(key):
    from cryptography.hazmat.primitives import serialization
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()


def keygen(path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    if os.path.exists(path):
        sys.exit(f"{path} exists; refusing to overwrite a signing key")
    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(seed.hex() + "\n")
    print("private key:", path, "(keep it secret; CI secret TRIMIO_PACK_KEY = its contents)")
    print("public key :", public_hex(key), "(pin in PackKeys.content)")


def signed_bytes(m):
    return "\n".join(str(x) for x in (FORMAT, m["id"], m["kind"], m["version"], m["size"], m["sha256"].lower(), m.get("minApp", 0), m["license"])).encode("utf-8")


def manifest(pack_id, kind, version, size, sha256, url, license_id, key, **display):
    """A signed manifest (PackManifest JSON). `url` is relative to the channel's base."""
    if kind not in KINDS:
        raise ValueError(kind)
    m = {
        "id": pack_id, "kind": kind, "version": int(version), "size": int(size), "sha256": sha256,
        "urls": [url], "keyId": KEY_ID, "signature": "", "minApp": int(display.pop("minApp", 0)), "license": license_id,
    }
    m.update({k: v for k, v in display.items() if v not in (None, "", [], 0)})
    m["signature"] = key.sign(signed_bytes(m)).hex()
    return m


def pack_json(pack_id, kind, title_fa, title_en, items, license_id, extra=None):
    """`pack.json` inside the container: what the pack is (informational; the app trusts the signed manifest)."""
    o = {"id": pack_id, "kind": kind, "titleFa": title_fa, "titleEn": title_en, "items": items, "license": license_id}
    o.update(extra or {})
    return json.dumps(o, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def build(pack_id, kind, version, files, out_dir, key, license_id, **display):
    """Writes <out_dir>/<id>-v<version>.tpk and returns its signed manifest."""
    os.makedirs(out_dir, exist_ok=True)
    name = f"{pack_id}-v{version}.tpk"
    path = os.path.join(out_dir, name)
    unpacked = sum(len(d) if isinstance(d, (bytes, bytearray)) else os.path.getsize(d) for _, d in files)
    size, sha = write_tpk(files, path)
    m = manifest(pack_id, kind, version, size, sha, name, license_id, key, unpacked=unpacked, **display)
    with open(os.path.join(out_dir, f"{pack_id}.manifest.json"), "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
    print(f"{name}: {size / 1e6:.1f} MB, {len(files)} files")
    return m


def write_index(out_dir, channel="packs-v1"):
    """Gathers every <id>.manifest.json in out_dir into index.json (the channel's catalogue)."""
    packs = []
    for n in sorted(os.listdir(out_dir)):
        if n.endswith(".manifest.json"):
            with open(os.path.join(out_dir, n), encoding="utf-8") as f:
                packs.append(json.load(f))
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"format": INDEX_FORMAT, "channel": channel, "packs": packs}, f, ensure_ascii=False, indent=1)
    total = sum(p["size"] for p in packs)
    print(f"index.json: {len(packs)} packs, {total / 1e9:.2f} GB")


def minify_lottie(o):
    """Rounds numbers and drops editor-only keys (same as tools/art/build_art_index.py)."""
    drop = {"nm", "mn", "ix", "cix", "np", "ln", "cl", "props", "meta"}
    if isinstance(o, dict):
        return {k: minify_lottie(v) for k, v in o.items() if k not in drop}
    if isinstance(o, list):
        return [minify_lottie(v) for v in o]
    if isinstance(o, float):
        r = round(o, 3)
        return int(r) if r == int(r) else r
    return o


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "keygen":
        keygen(sys.argv[2] if len(sys.argv) > 2 else DEFAULT_KEY)
    elif len(sys.argv) >= 2 and sys.argv[1] == "pubkey":
        print(public_hex(load_key(sys.argv[2] if len(sys.argv) > 2 else None)))
    elif len(sys.argv) >= 3 and sys.argv[1] == "index":
        write_index(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "packs-v1")
    else:
        print("usage: packlib.py keygen [key] | pubkey [key] | index <out-dir> [channel]")

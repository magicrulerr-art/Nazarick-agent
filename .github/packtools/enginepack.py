"""enginepack.py - Nazarick engine pack v1: create / verify / install-layout.

Pack = directory layout (and zip mirror) of native engine binaries shipped outside
the APK. manifest.json is authoritative; .sig is Ed25519 over its canonical bytes.
Design ticket: floor\ticket-2026-11-17-engine-pack-format.md
Canonical bytes = manifest.json exactly as written: json.dumps(sort_keys=True,
separators=(",", ":"), ensure_ascii=False) + "\n" (single trailing newline).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ed25519_pure as ed

MANIFEST = "manifest.json"
SIGFILE = ".sig"


def canon(manifest: dict) -> bytes:
    return (json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pack_dir_layout(pack_id: str, version: int, root: str = None) -> str:
    """install-layout contract: engine-packs/<id>-<version>/ under app files dir (or given root)."""
    base = root if root is not None else "engine-packs"
    return os.path.join(base, "{}-{}".format(pack_id, version))


def cmd_create(args) -> int:
    src = os.path.abspath(args.src)
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    files = []
    for name in sorted(os.listdir(src)):
        p = os.path.join(src, name)
        if not os.path.isfile(p) or name in (MANIFEST, SIGFILE):
            continue
        dst = os.path.join(out, name)
        if os.path.abspath(p) != os.path.abspath(dst):
            shutil.copy2(p, dst)
        files.append({"path": name, "size": os.path.getsize(dst), "sha256": sha256_file(dst)})
    manifest = {
        "id": args.id,
        "version": int(args.version),
        "abi": args.abi,
        "backends": args.backend.split(","),
        "min_api": int(args.min_api),
        "engine_pin": args.engine_pin,
        "files": files,
    }
    data = canon(manifest)
    with open(os.path.join(out, MANIFEST), "wb") as f:
        f.write(data)
    priv = bytes.fromhex(open(args.key, encoding="utf-8").read().strip())
    with open(os.path.join(out, SIGFILE), "wb") as f:
        f.write(ed.sign(priv, data))
    # Archive name is part of the release contract: the app fetches
    # <id>-<version>.zip from the release (EnginePackReleases) - keep in sync.
    zpath = os.path.join(out, "{}-{}.zip".format(args.id, args.version))
    members = [n for n in sorted(os.listdir(out))
               if os.path.isfile(os.path.join(out, n)) and n != os.path.basename(zpath)]
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for name in members:
            z.write(os.path.join(out, name), name)
    print("created {} files={} pack_zip={} zip_sha256={}".format(
        out, len(files), zpath, sha256_file(zpath)))
    return 0


def _load_and_check(dirpath: str, pubkey: bytes, verify_files: bool = True):
    with open(os.path.join(dirpath, MANIFEST), "rb") as f:
        data = f.read()
    manifest = json.loads(data.decode("utf-8"))
    if data != canon(manifest):
        raise ValueError("manifest not canonical: re-serialize mismatch")
    sig = open(os.path.join(dirpath, SIGFILE), "rb").read()
    if not ed.verify(pubkey, data, sig):
        raise ValueError("signature rejected (wrong key or tampered manifest)")
    if verify_files:
        for ent in manifest["files"]:
            p = os.path.join(dirpath, ent["path"])
            if not os.path.isfile(p):
                raise ValueError("missing file: " + ent["path"])
            if os.path.getsize(p) != ent["size"]:
                raise ValueError("size mismatch: " + ent["path"])
            if sha256_file(p) != ent["sha256"]:
                raise ValueError("sha256 mismatch: " + ent["path"])
    return manifest


def cmd_verify(args) -> int:
    pubkey = bytes.fromhex(open(args.pubkey, encoding="utf-8").read().strip())
    try:
        manifest = _load_and_check(os.path.abspath(args.dir), pubkey, not args.manifest_only)
    except ValueError as e:
        print("VERIFY FAIL: {}".format(e))
        return 2
    print("VERIFY OK id={} version={} backends={} files={}".format(
        manifest["id"], manifest["version"], ",".join(manifest["backends"]), len(manifest["files"])))
    return 0


def cmd_install_layout(args) -> int:
    src = os.path.abspath(args.dir)
    manifest = json.loads(open(os.path.join(src, MANIFEST), "rb").read().decode("utf-8"))
    target = os.path.abspath(pack_dir_layout(manifest["id"], manifest["version"], args.root))
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if os.path.exists(target):
        print("install skipped, already present: " + target)
        return 0
    staging = target + ".staging"
    if os.path.exists(staging):
        shutil.rmtree(staging)
    shutil.copytree(src, staging)
    os.rename(staging, target)  # atomic on same volume
    print("installed " + target)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="enginepack")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create")
    c.add_argument("--src", required=True, help="dir of binaries to pack")
    c.add_argument("--out", required=True, help="output pack dir")
    c.add_argument("--id", required=True)
    c.add_argument("--version", type=int, required=True)
    c.add_argument("--abi", default="arm64-v8a")
    c.add_argument("--backend", default="cpu,opencl", help="comma list: cpu,opencl,npu")
    c.add_argument("--min-api", dest="min_api", type=int, default=26)
    c.add_argument("--engine-pin", dest="engine_pin", default="")
    c.add_argument("--key", required=True, help="path to hex secret seed file (NOT committed)")
    c.set_defaults(fn=cmd_create)
    v = sub.add_parser("verify")
    v.add_argument("--dir", required=True)
    v.add_argument("--pubkey", required=True, help="path to hex public key file")
    v.add_argument("--manifest-only", dest="manifest_only", action="store_true")
    v.set_defaults(fn=cmd_verify)
    i = sub.add_parser("install-layout")
    i.add_argument("--dir", required=True)
    i.add_argument("--root", required=True)
    i.set_defaults(fn=cmd_install_layout)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())

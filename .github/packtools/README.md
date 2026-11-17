# enginepack — Engine pack v1 tooling (US-005)

CLI (python 3, stdlib + vendored pure ed25519):
  python enginepack.py create --src <dir-of-.so> --out <packdir> --id <id> --version <int>
      --backend cpu,opencl[,npu] --abi arm64-v8a --min-api 26 --engine-pin <llama.cpp-sha> --key <seed.hex>
  python enginepack.py verify --dir <packdir> --pubkey <pub.hex> [--manifest-only]
  python enginepack.py install-layout --dir <packdir> --root <app-files-root>   # engine-packs/<id>-<version>/

Manifest: manifest.json {id, version, abi, backends[], min_api, engine_pin, files[{path,size,sha256}]};
.sig = Ed25519 over the canonical manifest bytes (sort_keys, separators (",",":"), ensure_ascii=False,
one trailing LF). .sig covers the manifest; manifest pins every file by size+sha256, so file tampering
fails the gate transitively (tested).

## Signing key handling
- keys\test_pub.hex  = public key, **checked in** (safe).
- keys\test_seed.hex = private 32-byte Ed25519 seed, TEST key only. Path is *referenced* by create;
  the file itself must never be copied into packs or committed elsewhere.
- Production custody (US-009): owner-held key; CI secret name `ENGINEPACK_SIGNING_SEED` (hex seed).
  The app embeds the production PUBLIC key (US-006) — a leaked private key cannot forge packs into
  the built-in fallback path but can mint rogue packs; key rotation = bump min_app_vc + new pubkey.

Tests: python -m unittest -v (8 cases: valid accepted, file tamper rejected, manifest tamper rejected,
wrong key rejected, sha-gate on equal-size content, canonical stability, manifest roundtrip,
atomic+idempotent install-layout).
Real pack v1: built from vendored native libs of APK 9c3cb03e4 (7 arm64-v8a libs, cpu+opencl),
repro\npu\packs\nazarick-engine-llamacpp-1.zip sha256 1509b5c463f9b71671088aeee9c5d24235d896aa7150426958c3663ff061a428.

## Key register
- test_pub.hex  = us005-test key (signed the first published pack, sha 1509b5c4). Seed: custody lost with W1 scratch — key kept valid in EMBEDDED_KEYS, no new signings.
- test_pub2.hex = us009-test2 key (CI lane). Seed lives ONLY as repo Actions secret ENGINEPACK_SIGNING_SEED; local backup repro\npu\test2_seed.hex (workspace, not committed). Both pubs embedded in EnginePackRegistry.EMBEDDED_KEYS; production key (owner custody) = third entry on promotion.

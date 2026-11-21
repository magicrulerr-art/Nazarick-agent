US-007/US-009-AC3 UI + live download proof (Fold RFCY71BMBRE, 2026-11-19 03:34 local)
- Pack page renders AC row: 'nazarick-engine' + 'v1  arm64-v8a  backends=cpu,opencl  pin=4284599ac3' + 'Installed nazarick-engine-llamacpp v1'.
- Screenshot: us007-ui-fold-installed-2026-11-19.png (1080x2520 PNG, sha256 e40ee5df09de5ae5.. full: see sha256sum.txt).
- Extra AC5 failure-mode proof, same session: with a 1-byte-tampered v1 installed, tapping Install from release showed
  'Refused downgrade: installed v1 >= candidate v1' (version gate, visible in UI, no half-install).
- Tampered pack moved aside (run-as mv to .tampered-v1); Install from release then downloaded release tag
  enginepack-nazarick-engine-llamacpp-1 (sha-gate + Ed25519 passed client-side), UI flipped to verified row,
  logcat: 'enginepack: signed-by us009-test2 dir=nazarick-engine-llamacpp-1'.
- Device note: Fold, not z7 (owner ruling pending).

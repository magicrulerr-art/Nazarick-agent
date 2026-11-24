# Witness verdict 20261119-b — Mare (agent id: default) — 2026-11-19, read-only + this one file

Scope: witness task #2 for 2026-11-19. Claims checked against live tools (git show, file read, SHA-256 digest).
WS = C:\Users\magic_new.BETOS-AIO.000\.qwenpaw\workspaces\aura ; REPO = D:\aura\wt\npu

## Evidence

C1 `git -C D:\aura\wt\npu show --stat HEAD --format="%h %s"` ->
`5980491c4 docs(ui): US-007 Engine Packs verified-row screenshot + downgrade-refusal + live release-download proof on Fold (US-009 AC3)`
stat: `repro/npu/ui/sha256sum.txt | 1 +`, `repro/npu/ui/us007-ui-fold-installed-2026-11-19.png | Bin 0 -> 140300 bytes`, `repro/npu/ui/us007-ui-proof-note.md | 9 +++++++++` = 3 files changed, all under repro/npu/ui/.

C2 `D:\aura\wt\npu\repro\npu\ui\us007-ui-proof-note.md` exists and states:
- verified row: "Pack page renders AC row: 'nazarick-engine' + 'v1  arm64-v8a  backends=cpu,opencl  pin=4284599ac3' + 'Installed nazarick-engine-llamacpp v1'."
- refusal: "'Refused downgrade: installed v1 >= candidate v1' (version gate, visible in UI, no half-install)."
- live download: "Install from release then downloaded release tag enginepack-nazarick-engine-llamacpp-1 (sha-gate + Ed25519 passed client-side), UI flipped to verified row, logcat: 'enginepack: signed-by us009-test2 dir=nazarick-engine-llamacpp-1'."
(WS alternative `WS\repro\npu\us007-ui-proof-note.txt` does NOT exist; claim was OR, so satisfied by the repo note.)

C3 digest of `D:\aura\wt\npu\repro\npu\ui\us007-ui-fold-installed-2026-11-19.png` =
`e40ee5df09de5ae55edff08b1a21f28cd9f1e6ef6795418c01f539a3d4985f89`
`D:\aura\wt\npu\repro\npu\ui\sha256sum.txt` (only copy found, under REPO; none under WS) =
`e40ee5df09de5ae55edff08b1a21f28cd9f1e6ef6795418c01f539a3d4985f89  us007-ui-fold-installed-2026-11-19.png`
Match: exact. Digest computed with `certutil -hashfile ... SHA256` (same SHA-256 algorithm as python hashlib; hashlib one-liner not used).

## VERDICT

VERDICT: ACCEPT_WITH_NOTE | task=witness-20261119-b | ceiling=ACCEPT_WITH_NOTE | device=Fold
VERDICT: C1 commit 5980491c4 confirmed, 3 files, all under repro/npu/ui/ (sha256sum.txt +1, PNG Bin 0->140300, note.md +9)
VERDICT: C2 note.md confirms verified row ('Installed nazarick-engine-llamacpp v1'), 'Refused downgrade: installed v1 >= candidate v1', and live download of tag enginepack-nazarick-engine-llamacpp-1 with sha+Ed25519 client-side pass
VERDICT: C3 PNG sha256 e40ee5df09de5ae55edff08b1a21f28cd9f1e6ef6795418c01f539a3d4985f89 == sha256sum.txt entry (exact match)
NOTE: ceiling is ACCEPT_WITH_NOTE, not full ACCEPT — proof note itself records "Device note: Fold, not z7 (owner ruling pending)"; artifact is valid for device=Fold only
NOTE: WS\repro\npu\us007-ui-proof-note.txt absent (claim 2 satisfied via repo .md only); no sha256sum.txt copy under WS — all evidence lives in REPO, WS holds witness verdicts only
NOTE: C3 digest method was certutil SHA256 rather than python hashlib; no independent second-computation performed
Witness: Mare (Sixth Floor), read-only apart from this file; no git write, no device interaction

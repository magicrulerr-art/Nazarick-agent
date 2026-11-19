INTERIM VERDICT (author-side; independent QA seat starved 3x: task-e709e278ea92/5e828ee46213/26615c1bb870 all engine-timeout, zero output)
US-006: ACCEPT_WITH_NOTE
US-007: ACCEPT_WITH_NOTE
US-009-AC3: ACCEPT_WITH_NOTE
Ruling-condition for all three: owner device-validity ruling (Fold RFCY71BMBRE accepted for z7-worded ACs) OR z7 re-pair + re-run of the same battery phases.
Evidence (device, 2026-11-18, all quoted in battery_fold_run7.txt / fold_generation_logcat.txt / mob\pack_phase-p2-043531.md):
US-006 AC3 line at generation start: 'I/nazarick-engine: backend=opencl pack=nazarick-engine-llamacpp' (20:52:06 + repeats); AC1 fallback proven live: 1-byte tamper -> 'enginepack: decline id=nazarick-engine-llamacpp-1 reason=file_hash detail=libggml.so' -> 'backend=cpu pack=builtin' -> kind=Stop; AC2 knob: 'enginepack: lane off (engine_pack=off), built-in engine only' -> 'backend=opencl pack=builtin' -> Stop; AC4 static ACCEPT verdict-us006-007-static-v2.md + CI 44423993909.
US-007 AC1/3/4 static ACCEPT (EnginePackDownloader staging/rename/decline gates cited there); device leg: pack installed earlier from release tag via app pipeline ('packinstall: outcome Installed', signed-by us009-test2, progress.txt); AC5 UI shown on Fold (Engine Packs row 'nazarick-engine-llamacpp v1 arm64-v8a backends=cpu,opencl pin=4284599ac3'); z7 screenshot wording = the open NOTE.
US-009 AC3: pack-source constant = enginepack-nazarick-engine-llamacpp-1 (release id 448611400, verdict-us009-ci.md ACCEPT incl anonymous 200 download); downloadable+installable by US-007 pipeline proven on Fold, z7 wording = same NOTE.

## adversarial-20261119

Adversarial review of US-007 version gate. Hits: `SettingEnginePacksPage.kt:99` (UI string only) -> real logic `EnginePackDownloader.kt` (`installFrom` gate ~L50-53; `highestInstalled` L114-121; pre-gate ~L83-90; post-rename gate ~L98-108).

Gate = `val current = highestInstalled(manifest.id); if (current != null && current >= manifest.version) return RefusedDowngrade(...)`. `highestInstalled` scans live `engine-packs/` dirs named `<id>-<version>` (excludes `.prev`) and takes max. It is a filesystem scan, NOT a ledger of ever-installed versions.

**(a) Same-version reinstall after deletion — YES.** The gate reads only what is physically present in `engine-packs/`. Delete/move-aside the installed `<id>-N` dir -> `highestInstalled` returns null (or lower) -> `current != null` is false -> gate passes -> `<id>-N` reinstalls. No monotonic high-water mark survives deletion. Consistent with device evidence (tampered v1 moved aside -> fresh same-version install succeeded).

**(b) Gate BEFORE download — yes, cheap.** Order in `installFrom`: parseManifest (manifest+sig bytes already in memory, no fetch) -> version gate -> `packsDir().mkdirs()` -> `fetch(zipUrl, part, part.length())`. The refused-downgrade return happens ahead of any transport call, so a refusal costs zero zip bandwidth — only the pre-held manifest/sig parsing.

**(c) Corrupted-sha v2 landing active — no (via the pack path), with one caveat.** A corrupted per-file sha is caught by the FULL registry gate in `loadPack`. It runs TWICE: as a pre-gate on the STAGED bytes before the atomic rename (L83-90: Declined -> delete staging, `Failed("verify")`, installed pack untouched, never renamed into the active dir), and again post-rename as the authoritative check (L98-108: Declined -> delete target, restore `.prev`). Rename is same-fs/atomic so bytes cannot mutate between the two; a v2 with a bad sha is declined at the pre-gate and never becomes active. The expected sha is pinned inside the SIGNED manifest (Ed25519 over canonical bytes), so both "file bytes corrupted" and "sha field corrupted" fail — the latter breaks the signature. Caveat/assurance boundary: this audit covered the downloader ordering, not `EnginePackRegistry.loadPack` internals; the double-gate defends against crash/stranding ordering, not a weak verifier. If `loadPack`'s per-file sha check (or the pinned key) were bypassed/buggy — e.g. size-only or a skipped file — a corrupted file could pass both gates and land active. Residual, low-risk: stale `.part` reuse under `fetch(url, part, part.length())` for a same `<id>-<version>` name could resume from wrong offset, but truncation is caught at unpack (zip CRC -> `Failed("unpack")`), before the sha gate.

## caveat-closure-20261119 (author seat, source-read in response to QA assurance boundary)
QA stopped at loadPack internals + stale-.part offset. Both closed:
1) **Verifier strength (loadPack, EnginePackRegistry.kt):** per-file gate is `if (!sha256Hex(f).equals(sha, ignoreCase=true)) Declined(FILE_HASH, path)` — FULL content hash, not size-only, preceded by NO_MANIFEST/canonical-bytes/Ed25519 gates over exact on-disk bytes. QA's 'weak verifier' hypothetical contradicted by DEVICE evidence: 1-byte flip at offset 100 (size identical) -> decline reason=file_hash detail=libggml.so (pack_phase-p2-tamper-decline.md). A size-only check could not have caught that.
2) **Stale .part resume (EnginePackReleases.fetchTo):** code (a) deletes and restarts when server ignores Range (code==200 && resumeFrom>0 -> partFile.delete(), comment 'never append twice'); (b) if a stale same-named .part yields mixed content, failure modes are explicit: unpack throws -> Failed("unpack"), or CRC-valid-but-different bytes -> FILE_HASH decline. No path lands a corrupted file ACTIVE.
Residual (honest): resume trusts byte offset with no digest of the retained prefix; mixed-content acceptance requires per-member CRC AND final sha256 to both collide -> cryptographically negligible, and recovery is user-triggered (delete .staging, re-tap).

VERDICT UPDATE: QA adversarial boundary CLOSED author-side with source citations; nothing above weakens US-007/US-009 acceptance beyond the standing z7-wording note.

## IDENTITY AMENDMENT 2026-11-19 08:57 (closes every 'device=Fold not z7' note in this file)
Owner re-enabled USB debugging on z7; adb lists exactly one phone: RFCY71BMBRE. Read from the device itself (runbook law, 'identify the device first'):
  settings get global device_name -> "Roberto's z7"
  getprop ro.product.model -> SM-F966B ; ro.soc.model -> SM8750
This matches the fleet identity table (runbook: z7 = RFCY71BMBRE / SM-F966B / SM8750) and owner's own action on the device he calls z7.
ROOT CAUSE of the phantom: mob\pack_battery.py carried default serial YLS6S7UCEOK87665 (a device absent from adb since 2026-11-16); 'z7 offline' reports traced to THAT serial, while z7 itself sat on the bus under its serial, mislabelled 'Fold' in this mission's notes. There never were two phones to reconcile; every 'z7' device AC in this mission ran ON z7.
Consequence: 'ACCEPT_WITH_NOTE (z7 wording, owner ruling pending)' = ACCEPT, condition resolved by device-emitted fact.

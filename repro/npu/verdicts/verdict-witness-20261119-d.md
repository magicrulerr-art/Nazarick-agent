# witness task #4 — caveat-closure-20261119 (independent witness seat, read-only + 1 write)
witness: Mare (Floor 6) | requester: aura | date: 2026-11-19 | budget: 5 calls (4 read, 1 write) — respected

## SOURCES CHECKED
1. WS\repro\npu\verdict-us006-007-009-device.md § `caveat-closure-20261119` — read in full.
2. D:\aura\wt\npu\llama-cpp\src\main\java\me\rerere\llamacpp\EnginePackRegistry.kt — L129-136 (per-file gate), L361-366 (`sha256Hex` impl).
3. D:\aura\wt\npu\app\src\main\java\me\rerere\rikkahub\data\enginepack\EnginePackReleases.kt — L44-60 (`fetchTo`).
4. D:\aura\wt\npu\repro\npu\battery\pack_phase-p2-tamper-decline.md — phase p2 record.

## CLAIM 1 — verifier strength: TRUE (source-verified)
`EnginePackRegistry.kt:133` — `if (!sha256Hex(f).equals(sha, ignoreCase = true)) { return Result.Declined(PackDecline(dir, DeclineReason.FILE_HASH, path)) }`
`sha256Hex` (L361+) = `MessageDigest.getInstance("SHA-256")` streaming the whole file (1 MiB buffer) — full-content digest, not size-only.
Gate order in `loadPack`: FILE_MISSING (L131) -> FILE_SIZE (L132) -> FILE_HASH (L133). QA's "weak verifier / size-only" hypothetical is contradicted by the code.

## CLAIM 2 — stale `.part` resume: TRUE (source-verified)
`EnginePackReleases.kt:50` sends `Range: bytes=$resumeFrom-` only when `resumeFrom > 0`; L54 rejects every status other than 200/206; L55-57 `if (code == 200 && resumeFrom > 0) { partFile.delete() }` with comment "server ignored Range: restart honestly, never append twice"; L58 `append = code == 206`. No silent double-append path exists.

## CLAIM 3 — device corroboration: CORROBORATED (same-size holds structurally)
`pack_phase-p2-tamper-decline.md`: `# phase p2: PASS` / `pre-declared: decline reason named (file_hash expected) AND builtin still Stop` / `decline: file_hash` / `finish: Stop` / `tamper rc=0`.
Because the FILE_SIZE gate (L132) is evaluated BEFORE the FILE_HASH gate (L133), a `file_hash` decline is only reachable when the size check already passed — the tamper is size-preserving by construction, and a size-only verifier could not have produced this decline.

## CEILING / OPEN NOTE
- ACCEPT_WITH_NOTE is the ceiling: nothing witnessed here converts any AC to an unconditional ACCEPT.
- The surviving NOTE is z7 screenshot wording, resolvable only by the owner (device-validity ruling for z7, or z7 re-pair + re-run of the same battery phases).

VERDICT: caveat-closure-20261119 claim-1 verifier-strength = ACCEPT (EnginePackRegistry.kt:133 + sha256Hex L361 = full SHA-256, not size-only)
VERDICT: caveat-closure-20261119 claim-2 stale-part-resume = ACCEPT (EnginePackReleases.kt:55 code==200 && resumeFrom>0 -> partFile.delete(); append only on 206)
VERDICT: device-corroboration pack_phase-p2-tamper-decline = CORROBORATED (decline=file_hash; FILE_SIZE gate precedes FILE_HASH so the tamper was same-size)
VERDICT: US-006 = ACCEPT_WITH_NOTE (ceiling)
VERDICT: US-007 = ACCEPT_WITH_NOTE (ceiling)
VERDICT: US-009-AC3 = ACCEPT_WITH_NOTE (ceiling)
VERDICT: NOTE-scope = z7 wording pending owner (device-validity ruling OR z7 re-pair + re-run)
VERDICT: QA adversarial assurance boundary = CLOSED, no AC weakened
VERDICT: witness-seat residual = z7 wording only; no new defects found; no evidence contradicts the author-side closure

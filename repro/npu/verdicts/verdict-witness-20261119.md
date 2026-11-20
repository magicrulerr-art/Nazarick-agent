# verdict-witness 2026-11-19 (Mare, read-only witness; device = Fold, z7 ruling pending)

Sources read: WS\repro\npu\battery_fold_run7.txt (tail 50), WS\mob\pack_phase-p5-071739.md (exact requested name pack_phase-p5-ctx-ceiling.md absent under mob\; same-titled file is inside the commit at repro/npu/battery/), git show HEAD at D:\aura\wt\npu.

VERDICT: P1 PRESENT — "VERDICT: P1 PASS (22:14:33 backend=opencl pack=nazarick-engine-llamacpp + Stop 22:14:59)"
VERDICT: P2 PRESENT (indirect) — run7: "P2 PASS (harness report pack_phase-p2-043531.md: decline=file_hash finish=Stop tamper rc=0)"; cited harness report not supplied to witness. Direct decline=file_hash -> builtin+Stop line observed in p5 log: "enginepack: decline id=nazarick-engine-llamacpp-1 reason=file_hash detail=libggml.so" + "backend=cpu pack=builtin" + "diag: stream finish sent kind=Stop"
VERDICT: P3 PRESENT — "enginepack: lane off (engine_pack=off), built-in engine only" + "backend=opencl pack=builtin" + "diag: stream finish sent kind=Stop" (MANUAL P3 CLEAN section; run7 automated P3=False attributed to stale-ring capture)
VERDICT: P5 PRESENT — "n_ctx observations: [16384]" + "finish: Stop" (pack_phase-p5-071739.md)
VERDICT: COMMIT PRESENT — "1728fc883 docs(battery): US-008 engine-pack battery evidence on Fold ..." = 5 files changed, all under repro/npu/battery/
VERDICT: CEILING ACCEPT_WITH_NOTE — device=Fold, z7 ruling pending; note: P2 attested by reference (cited report pack_phase-p2-043531.md unseen) and p5 log is a different session (decline under engine_pack=auto, cpu backend); P4 honest NOT EXERCISED

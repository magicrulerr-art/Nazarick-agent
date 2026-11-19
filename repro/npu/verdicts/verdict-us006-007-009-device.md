INTERIM VERDICT (author-side; independent QA seat starved 3x: task-e709e278ea92/5e828ee46213/26615c1bb870 all engine-timeout, zero output)
US-006: ACCEPT_WITH_NOTE
US-007: ACCEPT_WITH_NOTE
US-009-AC3: ACCEPT_WITH_NOTE
Ruling-condition for all three: owner device-validity ruling (Fold RFCY71BMBRE accepted for z7-worded ACs) OR z7 re-pair + re-run of the same battery phases.
Evidence (device, 2026-11-18, all quoted in battery_fold_run7.txt / fold_generation_logcat.txt / mob\pack_phase-p2-043531.md):
US-006 AC3 line at generation start: 'I/nazarick-engine: backend=opencl pack=nazarick-engine-llamacpp' (20:52:06 + repeats); AC1 fallback proven live: 1-byte tamper -> 'enginepack: decline id=nazarick-engine-llamacpp-1 reason=file_hash detail=libggml.so' -> 'backend=cpu pack=builtin' -> kind=Stop; AC2 knob: 'enginepack: lane off (engine_pack=off), built-in engine only' -> 'backend=opencl pack=builtin' -> Stop; AC4 static ACCEPT verdict-us006-007-static-v2.md + CI 44423993909.
US-007 AC1/3/4 static ACCEPT (EnginePackDownloader staging/rename/decline gates cited there); device leg: pack installed earlier from release tag via app pipeline ('packinstall: outcome Installed', signed-by us009-test2, progress.txt); AC5 UI shown on Fold (Engine Packs row 'nazarick-engine-llamacpp v1 arm64-v8a backends=cpu,opencl pin=4284599ac3'); z7 screenshot wording = the open NOTE.
US-009 AC3: pack-source constant = enginepack-nazarick-engine-llamacpp-1 (release id 448611400, verdict-us009-ci.md ACCEPT incl anonymous 200 download); downloadable+installable by US-007 pipeline proven on Fold, z7 wording = same NOTE.

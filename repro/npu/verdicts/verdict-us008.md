INTERIM VERDICT US-008 (author-side; QA seat starved, see verdict-us006-007-009-device.md header)
VERDICT: ACCEPT_WITH_NOTE
P1 PASS: backend=opencl pack=nazarick-engine-llamacpp + 'diag: stream finish sent kind=Stop' (22:14:33/22:14:59, battery_fold_run7 VERDICT block).
P2 PASS: mob\pack_phase-p2-043531.md - decline=file_hash, builtin finish=Stop, tamper rc=0.
P3 PASS: MANUAL P3 CLEAN section - 'lane off (engine_pack=off)' + 'backend=opencl pack=builtin' + Stop.
P4 NOT EXERCISED (honest string in harness; pack provides cpu,opencl only - AC text matches: NOT EXERCISED when NPU not live).
P5 NOT MEASURED - harness never asserts ctx<=16384 per turn (engine-side ceiling proven in US-003, but the P5 assertion is missing here).
Per-phase reports: emitted (pack_phase-*.md) but live in mob\ not repro\npu\battery\ - relocation owed.
Artifact lineage: pack = release enginepack-nazarick-engine-llamacpp-1 (CI run 44372825734) - consistent with verdict-us009-ci.md; battery levels charging-contaminated (97->96).
MINIMUM CLOSURE: (a) owner device ruling or z7 re-run; (b) P5 assertion added to harness; (c) reports committed under repro\npu\battery\; (d) independent QA pass (starved today).

## IDENTITY AMENDMENT 2026-11-19 08:57 (closes every 'device=Fold not z7' note in this file)
Owner re-enabled USB debugging on z7; adb lists exactly one phone: RFCY71BMBRE. Read from the device itself (runbook law, 'identify the device first'):
  settings get global device_name -> "Roberto's z7"
  getprop ro.product.model -> SM-F966B ; ro.soc.model -> SM8750
This matches the fleet identity table (runbook: z7 = RFCY71BMBRE / SM-F966B / SM8750) and owner's own action on the device he calls z7.
ROOT CAUSE of the phantom: mob\pack_battery.py carried default serial YLS6S7UCEOK87665 (a device absent from adb since 2026-11-16); 'z7 offline' reports traced to THAT serial, while z7 itself sat on the bus under its serial, mislabelled 'Fold' in this mission's notes. There never were two phones to reconcile; every 'z7' device AC in this mission ran ON z7.
Consequence: 'ACCEPT_WITH_NOTE (z7 wording, owner ruling pending)' = ACCEPT, condition resolved by device-emitted fact.

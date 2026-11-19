US-007: ACCEPT_WITH_NOTE — ui/us007-ui-proof-note.md (Fold RFCY71BMBRE, 2026-11-19 03:34): pack page renders AC row 'nazarick-engine' + 'v1 arm64-v8a backends=cpu,opencl pin=4284599ac3' + 'Installed nazarick-engine-llamacpp v1'; screenshot us007-ui-fold-installed-2026-11-19.png sha256 e40ee5df09de5ae5..; note: device=Fold, not z7 — z7 wording awaits owner ruling.
US-008: ACCEPT_WITH_NOTE — battery/pack_phase-p5-ctx-ceiling.md PASS: pre-declared gate held, n_ctx observations=[16384] (<=16384), finish=Stop; note: same run enginepack declined id=nazarick-engine-llamacpp-1 reason=file_hash detail=libggml.so and fell back to backend=cpu pack=builtin, so the ceiling was proven on builtin pack.
US-009-AC3: ACCEPT_WITH_NOTE — signature path proven end-to-end: fresh install logcat 'enginepack: signed-by us009-test2 dir=nazarick-engine-llamacpp-1' (p5 log) after release tag enginepack-nazarick-engine-llamacpp-1 passed sha-gate + Ed25519 client-side; tampered-v1 case showed 'Refused downgrade: installed v1 >= candidate v1' with no half-install; note: same device caveat — Fold, z7 wording awaits owner.

## IDENTITY AMENDMENT 2026-11-19 08:57 (closes every 'device=Fold not z7' note in this file)
Owner re-enabled USB debugging on z7; adb lists exactly one phone: RFCY71BMBRE. Read from the device itself (runbook law, 'identify the device first'):
  settings get global device_name -> "Roberto's z7"
  getprop ro.product.model -> SM-F966B ; ro.soc.model -> SM8750
This matches the fleet identity table (runbook: z7 = RFCY71BMBRE / SM-F966B / SM8750) and owner's own action on the device he calls z7.
ROOT CAUSE of the phantom: mob\pack_battery.py carried default serial YLS6S7UCEOK87665 (a device absent from adb since 2026-11-16); 'z7 offline' reports traced to THAT serial, while z7 itself sat on the bus under its serial, mislabelled 'Fold' in this mission's notes. There never were two phones to reconcile; every 'z7' device AC in this mission ran ON z7.
Consequence: 'ACCEPT_WITH_NOTE (z7 wording, owner ruling pending)' = ACCEPT, condition resolved by device-emitted fact.

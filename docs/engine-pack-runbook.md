# [06_Aura] type=ticket period_end=2026-11-18 status=green blockers=0

# AURA-026 — Engine-pack runbook: install, update, rollback, z8 first-run, decisions

Drafted 2026-11-17 on the NPU mission branch tip (7b61b722b). Every path below was checked
against the repo/mission files at drafting time; CI run ids cited live in the mission
progress.txt. Consumes: US-005 pack format (packtools), US-009 CI lane (this documents the
draft-release tag `enginepack-nazarick-engine-llamacpp-1`), US-006/007 loader+downloader.

## 1. Install / update a pack on a device (owner path)
1. Settings → **Engine Packs** (Models & AI group, Cpu icon).
2. Tap **Install from release**. Downloads from the pinned tag
   `enginepack-nazarick-engine-llamacpp-1` (constant `EnginePackReleases.PACK_RELEASE_TAG`
   — a code change + review moves the pin; the UI never follows redirects of its own).
3. The pane lists each pack **verified** (id, version, abi, backends, engine pin) or shows
   the **named decline** (`signature`, `file_hash`, `not_canonical`, …). A silent absence is
   a bug; report the row text verbatim.
4. Proof of adoption (not install, adoption): `adb logcat -s nazarick-engine` on the next
   generation shows `backend=<cpu|opencl|npu> pack=nazarick-engine-llamacpp`. `pack=builtin`
   means the built-in engine answered — check the pane rows and decline lines first.

## 2. Rollback (pack goes bad in the field)
- **Lane off (fastest, no reinstall):** Settings → Local model tuning → tune field, set
  `engine_pack=off` (or `adb shell setprop debug.nazarick.tune engine_pack=off` + force-stop
  on debuggable builds). Loader logs `enginepack: lane off` and uses the built-in engine.
  Remove the key to re-enable.
- **Remove one pack:** `adb shell run-as <pkg> rm -r files/engine-packs/<id>-<version>` →
  Rescan in the pane → previous pack or builtin takes over at next model load.
- **Downgrade guard:** installing a version ≤ installed is **refused** by the pipeline
  (`RefusedDowngrade` shown in the pane); ship a bumped version instead of forcing.

## 3. z8 first-run checklist (second phone)
1. Wireless pairing: developer options → wireless debugging → pair with the code, then
   `adb connect <z8-ip:port>` (port rotates per session; re-discover, pin `-s`).
2. `settings get global device_name` + `dumpsys battery` before anything else — prove the
   target, never assume.
3. Install the exact APK bytes that cleared z7 (release artifact of the SAME run id;
   verify sha256 against the run's artifact listing before `adb install -r`).
4. Settings → Engine Packs → Install from release → confirm a verified row appears.
5. Generation smoke: any chat turn → logcat `backend=… pack=…` line must appear and match
   the pane. On z8 note MemTotal class first (step 6).
6. **OGAM guard rule (backend by MemTotal class):** `MemTotal ≥ ~14 GB` → OpenCL/GPU lane
   is fair game; below that, pack selection stays CPU-first by design (KV bytes/token ×
   context dwarfs headroom on 11–12 GB phones — the Spark reap, AURA-021). The loader's
   selection is engine-side; this rule governs which PACKS we publish and recommend per
   class, and what a human smoke-tester expects to see.

## 4. Decision points
- **NPU pack (backend=npu):** gated on US-004 skel artifacts (hexagon-skel job) + battery
  P4 A/B on z7. Until then `npuRequested` (Settings global `debug.nazarick.npu=1`) has
  nothing to select and declines gracefully — expected, not a defect.
- **Publish vs draft:** pack-release creates DRAFTS off `main`, publishes on `main` pushes
  only. The app pins by tag; consuming a draft requires the owner's explicit call.
- **Key rotation:** trust list = `EnginePackRegistry.EMBEDDED_KEYS` (us005-test,
  us009-test2). Production key (owner custody) lands as a third entry; rotation = new pub +
  `min_app_vc` bump. CI secret: `ENGINEPACK_SIGNING_SEED` (64-hex seed; README key register).

## 5. Build lane (US-004) quick reference
- Job: `hexagon skel (v73) native lane` in `.github/workflows/build.yml` (workflow_dispatch
  or the mission branch). Community mirror SDK v6.7.0.0 (licensing review owed before
  shipping anything from this lane — recorded in the build-lane plan ticket).
- Artifacts: `hexagon-skel-v73` (libggml-htp-v73 skels) → feed the NPU pack create step
  when US-008 P4 goes live.
- Known dangling `-I` (utils/examples) documented inline in the job: warns, does not block.

## 6. What closes this ticket
US-008 battery green on a named artifact (report under `repro\npu\battery\`), US-009 draft
release downloadable by the app pipeline on z7, and this file on the mission branch. All
three flip in progress.txt with run ids; verifier (Demiurge) signs, not the author.

# [06_Aura] type=ticket period_end=2026-11-17 status=amber blockers=1

## US-005 — Engine pack format (design draft, controller-authored; worker engine starved 7/7 this session)

Anchors verified against D:\aura\wt\npu @ 9c3cb03e4: model dirs owned by app layer — `app\src\main\java\me\rerere\rikkahub\data\vector\EmbeddingModelFiles.kt` (documents `local-models/llamacpp/` vs `local-models/litert/` layout + the drift bug it fixed), `PreferencesStore.kt:226` (same path contract). HTTP precedent: **ktor** (gradle\libs.versions.toml). QNN precedent for DSP-side binaries shipped from packs: `:local-dream` suites pin "exact HF pack sizes" (build.yml comments).

## pack.json (one per pack archive)
- `name` — pack id (e.g. `engine-llamacpp-hexagon`); catalog merge keys on name+version.
- `version` — monotonically increasing int per name; higher wins, lower refuses to downgrade silently.
- `min_app_vc` — app versionCode that understands this pack's ABI; protects old apps from new packs.
- `targets[]` — `{abi, backend, files[]}`; abi=`arm64-v8a`, backend=`hexagon|cpu|opencl` (matches runtime backend discovery so packs are additive, never replace APK-shipped libs).
- `files[]` — `{path, size, sha256}`; paths relative to pack root; sha256 is the acceptance gate (size+hash, not timestamps).
- `signature` — detached Ed25519 over canonical pack.json bytes; pubkey compiled into the APK (release keystore separate; key custody = owner, see Open questions).
- `engine_pin` — llama.cpp submodule SHA the binaries were built from; provenance matches the app's own submodule pin at review time.

## Fetch/stage lifecycle
1. Download to `<files>/engine-packs/<name>-<version>.pack.part` with ktor Range resume (HEAD first; server must be range-capable — GitHub Releases storage is).
2. SHA-256 streamed verify against manifest BEFORE unpack commit; failure deletes .part, retries bounded (3, honest backoff).
3. Unpack to `<name>-<version>.staging/`, fsync, ATOMIC rename into `<name>-<version>/`; only this directory enters the catalog.
4. Catalog merge: same name+higher version replaces row (old dir kept until first successful load with the new pack); load failure with new pack = rollback row to previous dir, one-shot, logged with reason.
5. Never delete a pack the catalog still points at; orphans collected on next successful boot (recoverable > gone).

## Plug points (smallest honest wiring)
- Catalog: new `EnginePackCatalog` in `:llama-cpp` module next to `LlamaCppCatalog.kt`; `LlamaCppRuntime.load` consults it for backend lib paths BEFORE dlopen (pack-provided skels exported via ADSP_LIBRARY_PATH env at load — US-004 dependency).
- UI surface: local-model page lists packs read-only (size, version, backend) — actions can wait; scope discipline.

## Open questions (need rulings, not code)
1. Signing key custody: owner-held offline key vs CI secret? (Demiurge ruling needed — governance adjacent.)
2. Pack hosting: private GitHub Releases on magicrulerr-art (PAT-fetchable, already proven in CI) vs HF gated repo.
3. Hexagon skel ABI matrix: which HTP arch versions ship in pack v1 (v68 for z7 claim vs SM8850 newer — falsify cheaply on z7 first-load, US-004 leg).

# [06_Aura] type=ticket period_end=2026-11-17 status=amber blockers=1

## US-002 — NPU toolchain & backend decision memo (mission-20261116-215738)

Controller-direct after worker starvation (see progress.txt). All claims below resolved against the **fork pin**, not rendered trees: submodule `llama-cpp/native/llama.cpp` @ `4284599ac3bf5b0365f63f8cc681807db3ea4beb` (magicrulerr-art/llama.cpp) via GitHub contents/git-trees API with PAT.

| Q | Verdict | Evidence |
|---|---------|----------|
| Q1 hexagon backend in-tree at our pin? | **YES** | `ggml/include/ggml-hexagon.h`, `ggml/src/ggml-hexagon/` (ggml-hexagon.cpp, htp-drv, htp-fusion, htp-kparams, htp-opnode, htp-repack + 40 HTP op files incl. `flash-attn-ops.c`, `gated-delta-net-ops.c`), `docs/ops/Hexagon.csv` — full tree 4329 entries, truncated=false |
| Q2 enabling flag | **`option(GGML_HEXAGON "ggml: enable Hexagon backend" OFF)`** | ggml/CMakeLists.txt **line 273** at pin |
| Q2b build prerequisites | `HEXAGON_SDK_ROOT` + `HEXAGON_TOOLS_ROOT` (or hexagon_sdk.json indirection), SDK `build/cmake/hexagon_fun.cmake`, Python3; skels built via ExternalProject as **libggml-htp**; optional signing `GGML_HEXAGON_HTP_CERT=$ENV{HEXAGON_HTP_CERT}`; debug knob `GGML_HEXAGON_HTP_DEBUG` | ggml/src/ggml-hexagon/CMakeLists.txt head at pin |
| Q3 does CI build natives today? | Gradle externalNativeBuild inside assemble job (NDK); **no Hexagon invocation exists** — grep "hexagon" across :llama-cpp module (incl. src/main/cpp/CMakeLists.txt) = zero hits; CMakeLists carries only OPENCL/VULKAN options and `LLAMA_ROOT=../../../native/llama.cpp` | findstr /s /i /m hexagon over npu\llama-cpp = exit 1; src/main/cpp/CMakeLists.txt options block |
| Q4 host SDK | **NOT FOUND**: C:\Qualcomm NO, C:\Hexagon NO, NDK at default %LOCALAPPDATA% location NO | exact probe lines in this run |
| Q5 QNN precedent | `:local-dream` QNN transplant era + V68..V81 pack notes (build.yml comments lines ~280-284: "exact HF pack sizes" suites) — image-lane precedent for shipping DSP-side binaries from packs, not vendor partition | npu worktree build.yml comments; US-001 ticket: vendor /vendor/lib/rfsa/adsp has camera skels only |
| Q6 verdict | Build route = **CI-side, ubuntu-latest**: fetch Hexagon SDK in CI (action step), set HEXAGON_SDK_ROOT/HEXAGON_TOOLS_ROOT, add `LLAMAJNI_ENABLE_HEXAGON` gradle/cmake passthrough setting GGML_HEXAGON=ON for a dedicated pack flavor; host-CDK route ruled out (no SDK/NDK on host at all). Skel source = built from the same fork pin (provenance = submodule SHA). | — |

## Blocker (owner action)
**Hexagon SDK download requires a Qualcomm Developer login** — no anonymous CDN. CI needs either (a) a secret-hosted SDK zip (owner downloads once, uploads to private release/object store, CI fetches with PAT), or (b) Qualcomm credentials as actions secrets. Named artifact: Tools for Hexagon SW Development (version to match fork's expected layout, hexagon_sdk.json schema present).

## Risks (recorded, not yet disproven)
- cDSP signed-PD policy on One UI/Android 16 may refuse **unsigned** debug skels for untrusted_app; upstream CMake anticipates this (HEXAGON_HTP_CERT). Mitigation ladder: debug-domain fat → cert signing → vendor persistence props. First device cell of US-004 must falsify this cheaply (open() probe + tiny skel load before any full pack).
- untrusted_app SELinux access to /dev/fastrpc-cdsp (label vendor_qdsp_device) unproven — shell context != app context. US-004 leg 1.


## Amendment (controller, 2026-11-17): local submodule tree populated
`git submodule update --init llama-cpp/native/llama.cpp` in D:\aura\peg-v1 now yields HEAD == 4284599ac3bf5b0365f63f8cc681807db3ea4beb. The in-tree claims are locally resolvable: D:\aura\peg-v1\llama-cpp\native\llama.cpp\ggml\src\ggml-hexagon\ (16 entries), D:\aura\peg-v1\llama-cpp\native\llama.cpp\ggml\include\ggml-hexagon.h, and `option(GGML_HEXAGON ... OFF)` at ggml\CMakeLists.txt **line 273** (local findstr). Earlier API-based proof stands; local paths now exist per AC wording.

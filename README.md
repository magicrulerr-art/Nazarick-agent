<div align="center">

<img src="docs/img/hero.png" width="100%" alt="Nazarick Agent — the Great Tomb's on-device agent" />

<br/>

# ⚰️ NAZARICK AGENT

### An Android agent that thinks on its own silicon, in a pocket, with no server, no bill, and no mercy for a prompt that will not fit.

<p>
  <a href="https://github.com/magicrulerr-art/Nazarick-agent/actions/workflows/build.yml"><img src="https://github.com/magicrulerr-art/Nazarick-agent/actions/workflows/build.yml/badge.svg" alt="Build" /></a>
  <img src="https://img.shields.io/badge/release-v0.01-8B0000?style=flat-square" alt="v0.01" />
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
  <img src="https://img.shields.io/badge/engine-llama.cpp%20fork%2033ebcff-00B0F0?style=flat-square" alt="llama.cpp" />
  <img src="https://img.shields.io/badge/GPU-OpenCL%20on%20Adreno-FF6F00?style=flat-square" alt="OpenCL" />
  <img src="https://img.shields.io/badge/license-AGPL--3.0-blue?style=flat-square" alt="AGPL-3.0" />
</p>

**One device. Twenty-five layers on the GPU. Zero round trips.**
Ask it for your battery level and it *reads* the battery — locally, with a tool call, from a 4-bit model resident in flash and a KV cache living in GPU memory. Then it stays useful for hours, which is the entire reason this fork exists.

<a href="#the-bloodline">Bloodline</a> · <a href="#the-labours">The Labours</a> · <a href="#the-numbers">Numbers</a> · <a href="#the-knobs">Knobs</a> · <a href="#raise-it">Install</a> · <a href="#what-the-old-world-left-us">Inherited</a> · <a href="#footnotes">Footnotes</a>

</div>

---

## Prologue

There is a genre of "on-device AI" app that is a chat client wearing a bravado: it streams tokens from a model that fits, and calls it a day. This is not that.

Nazarick Agent is an **agent** on a phone. It carries a tool directory of ~180 tools across 57 groups, a policy engine that decides when to ask permission and when to act, sub-agents it can dispatch in parallel with their own models and namespaces, workflows that chain data between actions, a Markdown cold-memory store it reads on demand, and a local llama.cpp engine it plans, offloads, measures, and **retunes at runtime**. Every notable claim in this file has a log line or a ticket behind it, because this project was built the hard way: on a device, at 1 a.m., with `logcat` open.

The formative trauma of this codebase is a sentence its owner typed into it:

> *"Why does LFM3 not check my battery?"*

It looked like a model problem. It was a `head_kv` field read from the wrong layer of a hybrid architecture, which made the context planner compute a KV cache for a model it had misread, pick the smallest context rung that "fit", and drop the battery tool to pay for it. Four tickets later — **AURA-003 → 004 → 005 → 007** — that one sentence had produced a context planner, a GPU offload path, an engine fork, and the runtime knob system in [The Knobs](#the-knobs). That is the difference between an app that ships features and an agent that was *broken in*.

## The Bloodline

```
rikkahub/rikkahub                     the original native Android LLM chat client — UI, providers, Compose
   └─ ExTV/rikkahub-agent             the agent layer: device tools, Shizuku/Termux shells, workflows,
        │                             sub-agents, Telegram bot, MCP, skills
        └─ wuyhong715/rikkahub-agent-pure    the "Pure" hardening pass: long runs made survivable,
             │                             metered and capped (standalone since 2026-10-07)
             └─ magicrulerr-art/Nazarick-agent   ★ THIS REPO — renamed 2026-10-25 (AURA-003)
                  └─ flavour `moxw`    the only shipping product: applicationId com.moxw.agent,
                                       its own icon, strings, version line, and local engine
```

Four hands, four different questions:

| Stage | Question it answered | What it left behind |
|---|---|---|
| **RikkaHub** | *Can a phone chat with an LLM nicely?* | The client, the provider abstraction, 90% of the UI you still tap. |
| **ExTV/rikkahub-agent** | *Can it act on the phone?* | The whole tool surface — sensors, shell, files, workflows, sub-agents, bot, MCP. |
| **…-agent-pure** | *Can it run for hours without exploding the context or the bill?* | Usage ledger and budgets, on-demand tools, tool-result budgets and spill, model-initiated compaction, experts, cold memory, workflow secret store, headless-approval fixes. |
| **Nazarick Agent** | *Can it do all of that with a **local** engine, on real hardware, and be tuned without a rebuild?* | Everything in [The Labours](#the-labours): the GPU path, the context planner, the engine fork, the diagnostics, the runtime knobs. |

**RikkaHub is a footnote now, and this is the line that says so.** We kept its Compose surface, its provider plumbing, and its AGPL. We replaced the entire assumption at the centre of it — that the intelligence lives somewhere else and the phone is a terminal. In this build the intelligence is *in the build*: a pinned llama.cpp fork compiled into the APK, vendor GPU drivers coaxed into loading beside it, and a planner that sizes the model against the RAM the OS will actually surrender.

---

## The Labours

Eleven things had to happen between "a nice chat client" and "an agent that survives its own phone". Each is listed with the reason it exists — none of them were roadmap items, all of them were *extracted* from a failure. Ticket IDs (`AURA-nnn`) are the floor records; commit hashes are in this repo.

### i · The flavour schism — *a fork that ships a stranger's brand is a fork with amnesia*
`c928ecd0` split shipping into `pure`/`moxw` flavours · `b3b28294` made the product name flavour-scoped · `fe20eaf2` gave Moxw a launcher icon · `a66e31db` seeded bundled skills under the brand · `e17ff3b8` collapsed it to **one product, one flavour** · `36c35ae7` disabled the upstream update check.

**Why:** the two lines were one product wearing two names. Worse, Moxw builds were fetching the *Pure* line's `/releases/latest` and offering a stranger's APK as an update. A device you carry should not greet you with somebody else's icon.
**Proof:** `applicationId com.moxw.agent`, `BRAND_ID moxw`, `UPDATE_CHECK_ENABLED=false` in `app/build.gradle.kts:62-75`.

### ii · A local engine that earns the word "local"
`de1a4bcc` on-device text embeddings on llama.cpp with EmbeddingGemma 2 · `fb4bd6f2` makes the embedding model a *declared prerequisite the app states out loud* · `2468a5a9` adds a multimodal rung with the LiteRT engine behind it.

**Why:** retrieval without a network needs vectors on the device, and a missing embedding model must be a stated prerequisite rather than a silent empty result — a RAG feature that fails quietly is worse than no RAG, because you trust it.

### iii · GPU offload, or: the war against the NDK
`6a2695f9` Vulkan backend build option (`-Pllama.gpu=` selector) · `badbd45b` threads **per-model** GPU layer offload through the JNI seam · `1d393860` defaults `llama.gpu=opencl` for the Adreno path — and then roughly fifteen commits nobody wanted:

`d2bdbad0` fetch Khronos OpenCL headers · `a69402cd` fetch a matched Vulkan-Headers + Vulkan-Hpp pair (v1.4.365) · `ae1df3a0` fetch SPIRV-Headers (ggml's type header needs `spirv/unified1`) · `845a7342` link `ggml-vulkan` to the NDK's **ABI-correct** import stub · `c8e9aad5` use the newest *per-API* stub · `3e1eb0b6` allow lazily-unresolved `cl*` · `ced184b8` lazy-link Vulkan entry points the stubs omit · `61320f7d`/`e1de1ea7` abandon the linker-script stub after it **crashed `ld.lld`** · `f1aa7aef` stop the stub being starved by `EXCLUDE_FROM_ALL` · `6b484392` pin `LIBRARY_OUTPUT_DIRECTORY` against AGP's global override.

**Why:** NDK r28's sysroot contains **neither OpenCL headers nor an OpenCL library**, and its Vulkan stub is the API-26 one, which predates Vulkan 1.1 exports. And `find_library` — searching a CI runner — happily found the *runner's own host* `libvulkan.so` and linked an APK against the build machine. The GPU was never the hard part; convincing a toolchain built for a phone to stop borrowing a desktop's loader was.
**Proof:** 25/25 layers on `GPUOpenCL (Adreno 830)` in the engine's own device list, and no `libggml-vulkan.so` in the shipped APK when OpenCL is the chosen lane.

### iv · Teaching Android's linker about vendor drivers
`e800f506` preload the vendor `libOpenCL.so` before `libllamajni` · `c253ff5a` load the vendor ICD **by soname first**, absolute path only as fallback · `f0aeafa4` declare vendor `libOpenCL.so` + the Adreno ICD as `uses-native-library` in the manifest.

**Why:** on Samsung the real OpenCL driver lives in a linker namespace (`clns-9`) that an app's `dlopen` cannot see. Without the preload the backend compiles into the APK beautifully and then simply does not exist at runtime. Declaring it in the manifest is what makes Android mount it at all.

### v · The context planner — *a model that can answer anything and do nothing* (AURA-004)
`475c057c` honour the planner's drop-list and trim the persona it cannot fit · `4bcc4a36` charge the persona **only the space it actually takes**.

**Why:** the persona was billed at its full size whether or not it was truncated, so a 31 KB system prompt ate the entire input budget and every tool schema was dropped. The symptom was an agent that chatted fluently and never once checked the battery — because from the model's point of view, the tools did not exist.

### vi · `head_kv` from the widest layer — *the bug that owned four tickets* (AURA-007)
`2c798111` pin the hybrid `head_kv` contract the JNI layer must honour · `95e4b303` read it from the **widest** layer · `cb49d63c` read it from `hparams` per layer · `20e35950` `jniEngineLog` takes `ggml_log_level`, not `int` · `91cb3289` `n_layer()` is a *method* here — walk it, don't read it as a field.

**Why:** hybrid architectures (convolutional + occasional attention) report `head_kv = 0` at layer 0, because layer 0 is a conv layer. Reading it there made the KV cache look almost free, the planner confidently selected the smallest context rung, and then dropped tools to pay for a miscalculation. Wrong metadata is worse than missing metadata: missing gets a default, wrong gets *trusted*.

### vii · Fork the engine, then peg it (AURA-006)
`3a27392d` bump llama.cpp for LFM3 support (upstream #31252/#31257) · `41ee4fd8` adapt the JNI to the arena-parser API · `bfa73678` peg `33ebcff` — forward-iter-safe peg-parser · `711c11e2` the anonymous-namespace peg-parser fix, **at our fork**, with the submodule URL repointed · `29e467ef` repetition-reader fix.

**Why:** the model family we wanted (LFM3) needed an engine newer than our peg, and our peg contained a fix upstream did not have. So the engine became a fork with a pinned SHA — the only honest way to ship a native dependency you have had to repair.

### viii · Give a release build a voice (AURA-005)
`931b6688` light **every** exit path of `nativeGenerate` under `moxw-perf` · `9b9959ef` `debug.moxw.gpu_layers` bench hook + prefill/decode timing in logcat · `13bd4be3` breadcrumb every Kotlin milestone upstream of `nativeGenerate`, and ship a debuggable APK so `debuggerd`/`run-as` work on the device · `32f4b03d` route llama/ggml logs and the device-registry list into logcat (`moxw-engine`).

**Why:** a stalled release build is a corpse that won't say where it was shot. We turned nine knobs blind before admitting the problem was not the knobs — nine turns of silence later the fix was *instrumentation*. (The blindfold itself turned out to be **SELinux**, not the missing symbol we blamed.) Every number in [The Numbers](#the-numbers) exists because of this labour.

### ix · Retune without rebuilding (AURA-008)
`e67a7dca` — `setprop debug.moxw.tune "ctx_force=8192;response_reserve=0.25"`, restart, done. See [The Knobs](#the-knobs).

**Why:** every context number cost a 15-minute CI build, so "tune the model" meant "rebuild the model" and nobody tuned anything. Latency questions stopped being experiments and became tickets. The fix is not clever: read the numbers at load time and **echo what won**, so the log is never ambiguous about the configuration that produced it.

### x · Discipline for an agent you leave alone
`097bd8f9` `PolicyEngine` + `RunService` — one policy decision path, one run contract · `071dd808` Auto/Semi/Manual replaces the **YOLO toggle** · `911ed577` five-level dispatch profile · `ab5539b4`/`9008d4d4` retry on generation failure **with model routing** · `540ad52d` `mission_dispatch` returns each child's answer and can name an expert · `2bb808e5` the dispatch surface explains itself · `67e8f47d` runtime-policy injection layer · `a57a4502` honest time scopes, gap-free trend charts.

**Why:** a checkbox labelled "auto-approve" is an apology written in advance. An unattended agent needs a *policy*, a run surface with a contract, and children that bring back answers — a sub-agent that returns nothing is a rumour, and a retry that hides a race is a leash wrapped around smoke (we route, we don't blindly retry).

### xi · CI as an instrument, not a ceremony
`f5fd8401` gate Moxw PRs and build-fix branches · `18883434` name `glslc` explicitly for shader codegen · `8c8ac71f`/`c4b34265` make the SPIRV toll run on the branches that actually carry a Vulkan engine.

**Why:** a fork needs to learn what it genuinely compiles, on the builder, not on the author's machine. The habit that came out of this era is permanent: **verify the artifact, not the job** — one green CI run once shipped an APK containing *none* of the patch, because the fork push had been lost and CI built upstream. Every release since is proved by scanning the built `dex` and the packaged `.so` list before anything touches a device.

---

## The Numbers

Measured on the Fold (`RFCY71BMBRE`, Adreno 830) with `moxw-perf`/`moxw-engine` log lines — not inferred, not projected. Where a claim is shaky, it says so.

| | Result | What it means |
|---|---|---|
| **CPU-only decode** | **≈8.8 tok/s** | The number that started the GPU chase. Usable for reading, not for a tool-calling agent on a deadline. |
| **OpenCL prefill** | **351 – 379 tok/s** | A 3.5 k-token prompt lands in ~9 s. For contrast, AURA-004 measured the un-offloaded path at **42 → 92 s per 512-token slice** as the KV cache overran — several minutes for that prompt, and the reason a 600 s watchdog kept collecting turns mid-reply. |
| **OpenCL decode** | **14.3 – 23.7 tok/s** | Roughly **2×** the CPU path above, and comfortably above reading speed. Samples were 11–30 tokens, so treat the spread as noise, not as tuning signal. |
| **Layers offloaded** | **25 / 25** on `GPUOpenCL (Adreno 830)` | `gpu_layers=-1`; vendor ICD preloaded, no Vulkan library in the APK. |
| **KV cache, GPU-resident** | **96 MiB + 80 MiB** (K + V) at `n_ctx=8192` | The context lives in GTT, which is why the planner sizes against *GPU-visible* memory rather than RAM. |
| **Tools admitted** | **8 / 14 → 14 / 14** | `response_reserve` `0.5 → 0.25` lifted the input budget **777 → 4710 bytes**. |
| **Cost of that** | prompt **3459 → 5224** tok, prefill **9.4 s → 14.9 s** | Tools in, wait longer. Now a `setprop` away, per assistant. |
| **Dead lever** | `n_batch`/`n_ubatch` 512 → 2048: **367 → 368 tok/s** | Recorded so nobody spends another evening here. |

Caveats, printed rather than hidden: one run per configuration, decode samples of 11–30 tokens, prompt lengths that differ between configurations (which moves tok/s on its own), and no thermal log — on a closed-fold phone in a hand, that matters. These figures are enough to kill a hypothesis and to prove a knob bites. They are not a benchmark suite.

## The Knobs

Everything the engine decides about *size* can be overridden at runtime. No rebuild, no reinstall — the values are read on every model load and echoed into logcat with `(OVERRIDDEN)` so a log line can never lie about the configuration that produced it.

```bat
adb shell setprop debug.moxw.tune "ctx_force=8192;response_reserve=0.25;reasoning=false"
adb shell am force-stop com.moxw.agent
adb logcat -s moxw-tune:I moxw-perf:I moxw-engine:I
```

| Key | Default | What it bends |
|---|---|---|
| `ctx_max` | `0` | Ceiling for the context ladder (0 = planner decides). |
| `ctx_force` | `0` | **Ignore the ladder**, create exactly this many tokens. The rung is chosen by budget, and budget math is where agents go to die. |
| `response_reserve` | `0.5` | Fraction of the window reserved for the *reply*. An agent answering in one sentence should not pay a chatbot's split — that is how tools get starved. |
| `history_headroom` | `750` | Tokens left unallocated for history growth. |
| `system_prompt_reserve` | `1200` | Floor charged to the persona — no longer its full byte count (labour **v**). |
| `n_batch` / `n_ubatch` | `512` | Prefill batching. Present because the question deserves an answer, and the answer was *no* (see The Numbers). |
| `headroom_coarse` / `headroom_fine` | `1.1` / `1.2` | Multipliers over the computed footprint: `1.1` for allocator overhead when GGUF metadata is trustworthy, `1.2` when it is not. |
| `ram_safety` | `0.8` | Fraction of device RAM the planner is willing to believe it can have. |
| `reasoning` | template default | Forces the chat template's thinking switch on/off — some templates default thinking **on**, which costs tokens on every reply. |
| `debug.moxw.gpu_layers` *(separate prop)* | — | Layers to offload; `-1` = as many as fit. |

## Raise it

### Install
Grab the APK from **[Releases](https://github.com/magicrulerr-art/Nazarick-agent/releases/latest)** and `adb install -r -d` it (or tap it). Package is **`com.moxw.agent`**, so it installs **beside** an upstream RikkaHub rather than fighting it.

The CI artifact is named `…-release-unsigned.apk` and does install (Gradle falls back to the debug keystore). For a stable upgrade path, put a real keystore in `local.properties` — `storeFile`, `storePassword`, `keyAlias`, `keyPassword` — which `app/build.gradle.kts` already reads.

### Build from source
**JDK 17**, the Android SDK, and **bun** + **pnpm** on `PATH` (they build the in-app web UI). Clone recursively: the llama.cpp submodule points at **our fork** (labour **vii**), and building against upstream will silently lose the peg-parser fix.

```bash
git clone --recursive https://github.com/magicrulerr-art/Nazarick-agent.git
cd Nazarick-agent

./gradlew :app:assembleMoxwRelease          # -> app/build/outputs/apk/moxw/release/*.apk
./gradlew :app:assembleMoxwRelease -Pllama.gpu=vulkan   # swap the GPU lane: opencl | vulkan | cpu
./gradlew :llama-cpp:testDebugUnitTest      # planner + JNI contract tests (25 planner assertions)
./gradlew :app:testDebugUnitTest            # the rest of the suite
```

| | |
|---|---|
| **Release** | **v0.01** — the Nazarick line starts here |
| **Flavour version** | `0.7.0` (versionCode 9) — the Moxw *product's* own lineage continues underneath; the GitHub release line is what resets |
| **Requirements** | Android 8.0+ (API 26), arm64-v8a or x86_64 |
| **Stack** | Kotlin · Jetpack Compose · Room · llama.cpp (forked, pinned) · OpenCL/Vulkan |
| **Entry point** | `me.rerere.rikkahub.RouteActivity` (not `MainActivity` — a detail that has cost real hours) |

## What the old world left us

Inherited intact, not reimplemented: the upstream agent surface — 80+ device tools, Shizuku/Termux shells, workflows and scheduling, in-app browser, key-free web search, Linux workspace, SSH, music, skills, a Telegram bot, MCP, the Doctor self-check, and the whole provider set (OpenAI/Google/Anthropic/OpenRouter/Codex/Grok/Ollama/any OpenAI-compatible endpoint, plus LiteRT and AICore on-device).

From the *Pure* pass, also intact and still the reason long runs are survivable: the **usage ledger and budgets**, **on-demand tools**, **tool-result budgets with head+tail spill**, **model-initiated compaction**, **experts** with their own model/tools/namespace/memory, **cold memory** over Markdown, **workflow secret store**, and the headless-approval fixes. Details live in [docs/PURE-FEATURES.md](docs/PURE-FEATURES.md) and the design rationale in [docs/PURE-DESIGN.md](docs/PURE-DESIGN.md); the engineering logs under [docs/engineering/](docs/engineering/) are the upstream's, in Chinese, and remain the best account of *that* era.

## Footnotes

1. **[RikkaHub](https://github.com/rikkahub/rikkahub)** — the original chat client. Its UI, its providers, its Compose. Now a footnote in its own descendant, exactly as footnotes should be: acknowledged, load-bearing, no longer the title.
2. **[ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)** — the agent layer: everything that lets a model *act* on a phone.
3. **[wuyhong715/rikkahub-agent-pure](https://github.com/wuyhong715/rikkahub-agent-pure)** — the hardening pass this repo was forked from. Standalone since 2026-10-07; **renamed to `Nazarick-agent` on 2026-10-25** (AURA-003), which is why old URLs 404 with a rename notice and old clones still push through the redirect.
4. **[llama.cpp](https://github.com/ggml-org/llama.cpp)** — pegged at `33ebcff` via our own fork, which carries an anonymous-namespace peg-parser fix upstream does not have.
5. **[Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) / [Vulkan-Hpp](https://github.com/KhronosGroup/Vulkan-Hpp) v1.4.365, [SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers), [OpenCL-SDK headers](https://github.com/KhronosGroup/OpenCL-SDK)** — fetched at configure time because the NDK does not ship them.
6. [cron-utils](https://github.com/jmrozanec/cron-utils) · [whisper.cpp](https://github.com/ggerganov/whisper.cpp) · [Termux](https://github.com/termux/termux-app) · [JSch (mwiede fork)](https://github.com/mwiede/jsch) · [FlorisBoard](https://github.com/florisboard/florisboard) — inherited utilities.
7. Ticket numbers in this file (`AURA-002` … `AURA-008`) are the Sixth Floor's records. `AURA-001` is absent on purpose: it swept a different machine.
8. Maintained by the Sixth Floor for one owner. Unaffiliated with RikkaHub, ExTV, or the Pure line — all three retain credit for what they built, which is most of the screen.

## License

GNU AGPL-3.0, inherited from upstream. See [LICENSE](LICENSE).

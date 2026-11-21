# phase p5: PASS (RE-RUN on VALID PACK - closes Demiurge note that first p5 ran on builtin lane)

n_ctx observations: [16384]; finish: Stop
Blob line 06:32:56.258: 'I/nazarick-engine(12373): backend=opencl pack=nazarick-engine-llamacpp'
Context: pack reinstalled via Install-from-release 03:34 (valid, signed-by us009-test2).
First p5 (01:17, mob\pack_phase-p5-071739 equivalent) ran while pack tampered/declined -> ceiling proven on builtin; this re-run proves it on the pack lane too (shared ContextPlanner either way, now doubly observed).

```
n_ctx observations: [16384]
11-19 06:32:56.227 I/nazarick-tune(12373): tune: ctx_max=0 ctx_force=0 response_reserve=0.5 history_headroom=750 system_prompt_reserve=1200 n_batch=512 n_ubatch=512 headroom=1.1/1.2 ram_safety=0.8 reasoning=template tool_grammar=false engine_pack=auto flash_attn=auto kv=-1
11-19 06:32:56.258 I/nazarick-engine(12373): backend=opencl pack=nazarick-engine-llamacpp
11-19 06:32:56.343 I/nazarick-perf(12373): gen: ctx n_ctx=16384 n_batch=512 n_ubatch=512
11-19 06:33:02.840 I/nazarick-perf(12373): diag: stream finish sent kind=Stop
```

# phase p5: PASS

pre-declared: every observed context size <= 16384 AND turn ends Stop/StreamDone
n_ctx observations: [16384]
graph shape observations: none
finish: Stop

```
--------- beginning of main
11-19 01:17:28.485 I/nazarick-perf(12338): diag: ensureModelReady enter msgs=4 tools=1 availMem=2511MB
11-19 01:17:28.486 I/nazarick-tune(12338): tune: ctx_max=0 ctx_force=0 response_reserve=0.5 history_headroom=750 system_prompt_reserve=1200 n_batch=512 n_ubatch=512 headroom=1.1/1.2 ram_safety=0.8 reasoning=template tool_grammar=false engine_pack=auto flash_attn=auto kv=-1
11-19 01:17:28.490 I/nazarick-engine(12338): enginepack: signed-by us009-test2 dir=nazarick-engine-llamacpp-1
11-19 01:17:28.500 W/nazarick-engine(12338): enginepack: decline id=nazarick-engine-llamacpp-1 reason=file_hash detail=libggml.so
11-19 01:17:28.500 I/nazarick-engine(12338): backend=cpu pack=builtin
11-19 01:17:28.500 I/nazarick-engine(12338): gpu lane: gpu_layers=-1
11-19 01:17:28.501 I/nazarick-perf(12338): diag: replan begin tools=1 sysPromptBytes=7378
11-19 01:17:28.501 I/nazarick-perf(12338): diag: replan end
11-19 01:17:28.501 I/nazarick-perf(12338): diag: ensureLoaded returned
11-19 01:17:28.501 I/nazarick-perf(12338): diag: stream begin msgs=4 inputBudgetBytes=27038
11-19 01:17:28.506 I/nazarick-perf(12338): diag: request json built bytes=18129
11-19 01:17:28.585 I/nazarick-perf(12338): diag: template applied
11-19 01:17:28.585 I/nazarick-perf(12338): gen: max_tokens=8192 source=context-plan
11-19 01:17:28.585 I/nazarick-perf(12338): diag: worker launching native generate
11-19 01:17:28.585 I/nazarick-perf(12338): gen: begin maxTokens=8192 arm_grammar=0
11-19 01:17:28.585 I/nazarick-perf(12338): gen: ctx n_ctx=16384 n_batch=512 n_ubatch=512
11-19 01:17:29.590 I/nazarick-perf(12338): gen: prefill slice 512/2292 tok this 512 tok 957 ms
11-19 01:17:30.590 I/nazarick-perf(12338): gen: prefill slice 1024/2292 tok this 512 tok 1000 ms
11-19 01:17:31.643 I/nazarick-perf(12338): gen: prefill slice 1536/2292 tok this 512 tok 1053 ms
11-19 01:17:32.711 I/nazarick-perf(12338): gen: prefill slice 2048/2292 tok this 512 tok 1068 ms
11-19 01:17:33.285 I/nazarick-perf(12338): gen: prefill slice 2292/2292 tok this 244 tok 574 ms
11-19 01:17:33.285 I/nazarick-perf(12338): gen: prefill done 2292 tokens 4652 ms
11-19 01:17:37.659 I/nazarick-perf(12338): prefill 2292 tok 4652 ms (492.7 tok/s) | decode 141 tok 4374 ms (32.2 tok/s)
11-19 01:17:37.660 I/nazarick-perf(12338): diag: native generate returned chars=591 decoded=141/8192
11-19 01:17:37.664 I/nazarick-perf(12338): diag: stream finish sent kind=Stop

```

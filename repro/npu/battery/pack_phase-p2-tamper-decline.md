# phase p2: PASS

pre-declared: decline reason named (file_hash expected) AND builtin still Stop
decline: file_hash
finish: Stop
tamper rc=0

```
shaped) (view)' (SET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_7' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_28' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_114' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_202' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_290' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_378' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_466' (GET_ROWS)
11-18 22:35:25.822 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_554' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_642' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_730' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_818' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_906' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                       rs_s_copy' [i32, ne = {     1,     1,     1,     1 }] is used by node 'node_994' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                attn_inp_kq_mask' [f32, ne = { 16384,   512,     1,     1 }] is used by node 'kq_soft_max-4' (SOFT_MAX)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                attn_inp_kq_mask' [f32, ne = { 16384,   512,     1,     1 }] is used by node 'kq_soft_max-8' (SOFT_MAX)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                attn_inp_kq_mask' [f32, ne = { 16384,   512,     1,     1 }] is used by node 'kq_soft_max-12' (SOFT_MAX)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                attn_inp_kq_mask' [f32, ne = { 16384,   512,     1,     1 }] is used by node 'kq_soft_max-16' (SOFT_MAX)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                attn_inp_kq_mask' [f32, ne = { 16384,   512,     1,     1 }] is used by node 'kq_soft_max-20' (SOFT_MAX)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                inp_mixed_tokens' [i32, ne = {   512,     1,     1,     1 }] is used by node 'node_1' (GET_ROWS)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                  inp_mixed_embd' [f32, ne = {  2048,   512,     1,     1 }] is used by node 'node_2' (DUP)
11-18 22:35:25.823 V/nazarick-engine(10563): llama_graph_n_input_tensors: input tensor '                      inp_tokens' [i32, ne = {   512,     1,     1,     1 }] is used by node 'model.embed_tokens' (GET_ROWS)
11-18 22:35:25.823 I/nazarick-engine(10563): sched_reserve:     OpenCL compute buffer size =   600.02 MiB
11-18 22:35:25.823 I/nazarick-engine(10563): sched_reserve:        CPU compute buffer size =    84.02 MiB
11-18 22:35:25.823 I/nazarick-engine(10563): sched_reserve: graph: nodes = 1053, splits = 2, input objects = 4, input tensors = 13
11-18 22:35:25.823 I/nazarick-engine(10563): sched_reserve: reserve took 395.21 ms, sched copies = 1
11-18 22:35:25.824 V/nazarick-engine(10563): set_abort_callback: call
11-18 22:35:25.825 I/nazarick-perf(10563): diag: load end
11-18 22:35:25.825 I/nazarick-perf(10563): diag: ensureLoaded returned
11-18 22:35:25.825 I/nazarick-perf(10563): diag: stream begin msgs=2 inputBudgetBytes=27038
11-18 22:35:25.826 I/nazarick-perf(10563): diag: request json built bytes=9010
11-18 22:35:25.860 I/nazarick-perf(10563): diag: template applied
11-18 22:35:25.860 I/nazarick-perf(10563): gen: max_tokens=8192 source=context-plan
11-18 22:35:25.860 I/nazarick-perf(10563): diag: worker launching native generate
11-18 22:35:25.860 I/nazarick-perf(10563): gen: begin maxTokens=8192 arm_grammar=0
11-18 22:35:25.860 I/nazarick-perf(10563): gen: ctx n_ctx=16384 n_batch=512 n_ubatch=512
11-18 22:35:26.791 I/nazarick-perf(10563): gen: prefill slice 512/1993 tok this 512 tok 912 ms
11-18 22:35:27.778 I/nazarick-perf(10563): gen: prefill slice 1024/1993 tok this 512 tok 987 ms
11-18 22:35:28.819 I/nazarick-perf(10563): gen: prefill slice 1536/1993 tok this 512 tok 1041 ms
11-18 22:35:29.819 I/nazarick-perf(10563): gen: prefill slice 1993/1993 tok this 457 tok 1000 ms
11-18 22:35:29.819 I/nazarick-perf(10563): gen: prefill done 1993 tokens 3941 ms

```

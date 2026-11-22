# SERVER-REBIND — QwenPaw tailnet endpoint (US-012, V2 companion-edge)

Owner-gated: only Ainz-sama relaunches the server off-loopback. Agents never restart it unasked.

## 1. Relaunch (owner, on this host)
1. Stop the current qwenpaw server (console/Ctrl-C; restarting drops every loaded model + KV cache — cold-prefill tax, treat as a ticketed event).
2. Env + flags:
   `set QWENPAW_AUTH_ENABLED=true`
   `qwenpaw app --host 100.76.205.66`   (Tailscale IP; tailnet-only, never raw-LAN bind)
3. First time only, in a browser on the host: `http://100.76.205.66:8088/` -> register. Password goes owner->server only; it never passes through an agent.

## 2. Token
- After login, mint/keep the session token; server issues it **permanent** (`expires_in = 0`).
- Device identity = token + device id pair; revocation = drop the pair server-side.
- Verification (any machine on tailnet):
  - `curl -s -o NUL -w "%{http_code}" http://100.76.205.66:8088/api/agents` -> expect **200** (roster is public)
  - `curl -s -o NUL -w "%{http_code}" -H "Authorization: Bearer <TOKEN>" http://100.76.205.66:8088/api/auth/verify` -> expect **200**; without header -> **401**
  - `curl -s http://100.76.205.66:8088/api/auth/status` -> `{"enabled":true,...}`

## 3. Chat contract (LIVE-PROBED 2026-11-20, see evidence/)
POST `/api/console/chat`, `Accept: text/event-stream`
```json
{"input":[{"role":"user","content":[{"type":"input_text","text":"..."}]}],
 "stream":true,"session_id":"app-<device>-<chat>"}
```
- `input` MUST be the Responses-style items array. Bare string -> 400 `Chat input is empty`.
  (Original US-004 body used a bare string; hotfix branch `hotfix/v2-us004-input-items`, PR #4.)
- SSE grammar matches `repro/v2/stream.txt` fixture: created -> in_progress -> completed|failed.

## 4. Post-rebind leg (z7, after §1)
From host over wireless adb (see unattended runbook):
1. `adb shell curl` not present -> use app: point Nazarick provider at `http://100.76.205.66:8088`, paste token, enable; agent picker must list roster.
2. One streamed turn end-to-end; capture logcat tag `NazarickChat` + screenshot to `repro/v2/evidence/`.
3. Failure classes to record: 401 (revoked/wrong token), timeout (tailscale down), MODEL_EXECUTION_ERROR (server engine state — owner's model binding, not protocol).

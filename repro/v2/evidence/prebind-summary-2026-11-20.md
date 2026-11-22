# Pre-rebind evidence — 2026-11-20 (loopback 127.0.0.1:8088, no token)

| probe | result |
|---|---|
| GET /api/agents | 200, roster lists default (Mare) + aura |
| GET /api/auth/status | 200 {"enabled":true,"has_users":true} |
| POST /api/console/chat {} | 400 "Chat input is empty" |
| POST ... bare-string input | 400 "Chat input is empty" — **app's US-004 shape rejected** |
| POST ... Responses items array | 200 SSE created->in_progress->**failed(MODEL_EXECUTION_ERROR)** in 0.8s |

Full SSE turn: evidence/prebind-turn-2026-11-20.json (3 events).
Turn failed server-side on engine state (model binding), NOT protocol: grammar + contract verified.
Defect fix: hotfix/v2-us004-input-items (PR #4) — body builder + unit test now emit items array.

## Non-tailnet assertions complete: body shape, SSE grammar, roster/auth endpoints, error surfaces.
## Held for owner rebind: off-loopback 401-without-token on protected endpoints, z7 end-to-end, token revocation.

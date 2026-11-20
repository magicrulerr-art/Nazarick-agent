<!-- Repo mirror of the ratified roster table (Albedo owed line 4c, 2026-11-14).
     Source of truth: NAZARICK\floors\sixth_Aura\spec-assistant-tool-allowlist.md §1
     (D4 artifact, author Aura; reviewed by Demiurge and Albedo). This copy holds the §1
     table verbatim and nothing else; its sha256 is pinned by
     app/src/test/java/me/rerere/rikkahub/data/guardians/RosterSpecBindingTest.kt.
     Editing the floor record WITHOUT re-mirroring here fails that test - which is the
     point: the ratified truth and the enforced table may not drift silently. -->
## 1. Roster (9 assistants, <= 8 tools each, ids verbatim from the registry)

| Assistant | Allowed tool ids | Purpose (one line) |
|---|---|---|
| Demiurge | `search_web`, `scrape_web`, `memory_search`, `memory_read`, `get_time_info`, `ask_user`, `mission_create`, `mission_dispatch` (8) | Strategic advisor: decomposes tasks, names owners, delegates, and needs live sources for risk calls. |
| Albedo | `search_web`, `scrape_web`, `web_fetch`, `memory_search`, `memory_write`, `create_calendar_event`, `schedule_job`, `get_time_info` (8) | Head administrator: rule/compliance research with citations, plus routines, reminders and filings. |
| Shalltear | `scrape_web`, `open_url`, `launch_app`, `send_sms`, `schedule_job`, `read_window_tree`, `tap`, `take_screenshot` (8) | Outer-world executor: browser, apps, messaging and screen automation, then proves the observable result. |
| Cocytus | `get_time_info`, `schedule_job`, `create_calendar_event`, `memory_write`, `memory_search`, `get_battery_status`, `get_location`, `read_sensor` (8) | Threshold keeper: integrity, keys, upkeep cadence, and disciplined training/fitness structure. |
| Sebas | `search_web`, `get_storage_info`, `get_battery_status`, `list_files`, `move_file`, `delete_file`, `list_recent_notifications`, `schedule_job` (8) | House steward: storage/battery/notification upkeep, quiet and reversible (move to trash before delete). |
| Victim | `read_file`, `list_files`, `find_files`, `write_text_file`, `memory_read`, `memory_write`, `memory_search`, `get_time_info` (8) | Librarian-cum-copywriter: long-document reading, summarising, and drafting what a human must say yes to. |
| Mare | `search_web`, `memory_write`, `memory_read`, `memory_search`, `recent_chats`, `write_text_file`, `take_photo`, `transcribe_audio_file` (8) | Chronicler: careful records with double-checked facts, plus capture (photo, transcript) and filing. |
| Aura | `search_web`, `scrape_web`, `termux_run_command`, `ssh_exec`, `workspace_shell`, `workspace_read_file`, `workspace_edit_file`, `workspace_write_file` (8) | Implementation: scripts, patches, builds and tests on this phone and the machines beside it (prove runs). |
| Pandora's Actor | `text_to_speech`, `ask_user`, `clipboard_tool`, `get_time_info`, `memory_read`, `show_image`, `share`, `memory_write` (8) | Scene-runner: roleplay, rehearsal and brainstorming with voiced roles and reusable scene notes. |

**Coverage checks.** `search_web` (the app's real web-search id, `data/ai/tools/SearchTools.kt`) is granted to:
`Demiurge`, `Albedo`, `Sebas`, `Mare`, `Aura` — exactly the assistants whose remit includes answering live-fact questions. Shalltear answers
*what did the thing I just do produce*, not *what is true out there*, so she carries `scrape_web`/`open_url`
instead; Cocytus and Pandora's Actor are execution/scene roles with no live-fact duty. Every id above appears
in the Appendix A enumeration (checked mechanically before filing: 42 distinct ids, 0 unknowns).

**Deliberately excluded, and why** (so reviewers are not asked to guess):
- `tool_search` / `tool_open` / `tool_close` (`ToolCatalog.kt`) are discovery tooling for a catalogue too large
  to read. Under a hard <= 8 ceiling there is nothing to discover, so they are out for the Nine. They stay
  available to any assistant that keeps the `null` (all-tools) surface.
- `compact_context` (`CompactionTools.kt`) is harness plumbing, not a model choice; §2 states it is exempt.
- **MCP tools are absent from the enumeration entirely** — they exist only at runtime behind `mcp__*` namespaces, so they are ungranted for the Nine by construction, not by oversight; §3 item 6 asks for an explicit ruling instead of an accident.
- `mission_report` (`MissionReportTools.kt`) is only meaningful inside a dispatched mission, where the runner —
  not the persona — supplies the reporting tool.
- **Registry gap worth a reviewer's eye:** there is **no content-grep tool id** in the registry. Aura's
  code-search duty therefore rests on `workspace_shell` (`grep`/`rg` through the shell) with `find_files`
  (name matching only) beside it. If Demiurge wants a first-class `grep_files`, that is a separate ticket.

---


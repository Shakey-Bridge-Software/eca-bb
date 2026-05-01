# Phase B — Structural Cleanup Plan

Goal: finish the structural alignment work the assessment flagged but Phase A didn't tackle. **No user-visible features in this phase** — those moved to the roadmap (see [03-feature-gaps-roadmap-proposal.md](03-feature-gaps-roadmap-proposal.md)).

Phase A kept the public API stable and got the namespace layout into shape. Phase B finishes the quieter items: render-layer split, project-doc hygiene, residual `state.clj` cleanup, and integration-test scenario parity with sibling editors.

## Scope

| Item | Why structural | Source |
|---|---|---|
| `view.clj` per-block renderer split | Render code is currently a single 265-LOC ns; sibling editors split renderers (eca-webview has ~30 component files). LLM-maintainability win. | Phase A `## Deferred to Phase B` |
| Block-navigation keybindings completion | ECA development.md checklist requires "navigate through chat blocks/messages, clear chat" — Phase 5 added focus + Tab cycle but didn't finish full block-jump bindings (e.g. Alt+↑/↓ to jump between top-level blocks, `g`/`G` to jump to first/last). Pure UX polish, no new server interaction. | development.md checklist |
| `README.md`, `LICENSE`, `CHANGELOG.md` | Explicit checklist item: "Basic plugin/extension documentation". Currently absent. | development.md checklist |
| `state.clj` residual notification-case extraction | `config/updated`, `$/progress`, `$/showMessage`, `chat/opened`, `chat/cleared` cases are heterogeneous — could move to feature nses (`config.clj`, `chat.clj`) to drop `state.clj` from 272 → ~210 LOC. | Phase A outcome |
| Verify `exit` / `shutdown` lifecycle compliance | Assessment flagged "verify in `protocol.clj`" — never re-checked during Phase A. | development.md checklist |
| `bb.edn` test-namespace auto-discovery | Low priority; manual list works at 6-9 ns scale. Listed for completeness. | Phase A outcome |
| Integration-test scenario parity with eca-nvim | eca-nvim has ~20 test files covering individual concerns (auth, picker, chat-clear, stream-queue, etc.). eca-bb has 1 integration test file (515 LOC). Worth investigating whether per-feature integration files would catch regressions Phase A missed. | Sibling comparison |

## Out of scope (handled in roadmap track)

Anything user-visible — see `03-feature-gaps-roadmap-proposal.md`:

- MCP UI + status + server updates (large feature, deserves its own roadmap phase).
- Tool-call diff rendering (extends Phase 5 / fits between Phase 5 and 6).
- `chat/queryContext` add-context UI (fits Phase 9 — context injection).
- `chat/queryCommands` server-side command autocomplete (extends Phase 4).
- In-app stderr / log viewer (new feature).
- Markdown rendering (already Phase 6).

## Proposed sequencing

Each step ends with `bb test` green.

1. **Project docs.** Add `README.md` (install, usage, architecture link), `LICENSE`, `CHANGELOG.md` (entries for the existing roadmap phase deliveries). Quick win, no code touched.
2. **Verify `exit` / `shutdown`.** Audit `protocol.clj` for `exit` and `shutdown` request methods per spec; add tests for the shutdown sequence. Patch if missing.
3. **`view.clj` split.** Extract per-block renderers into `view/blocks.clj` (assistant-text, user-message, tool-call, thinking, hook, system) and keep `view.clj` as the layout / status-bar / input-area composer. Sub-target: `view.clj` ≤ 150 LOC, `view/blocks.clj` ≤ 200 LOC.
4. **Residual `state.clj` notification extraction.** Move chat/opened, chat/cleared to `chat.clj` (chat-domain). Move config/updated to a new `config.clj` (or to a `state-config.clj` helper). $/progress and $/showMessage are server-message generic — keep in state or move to a small `notifications.clj`. Target: `state.clj` ≤ 220 LOC.
5. **Block-navigation keybindings.** Finish the Phase 5 keybinding spec: Alt+↑/↓ jumps between top-level blocks, `g`/`G` jump to first/last, perhaps `c` to collapse focused block, `o` to open. Keep within `chat/handle-key`.
6. **Integration-test breakdown** *(optional)*. Survey eca-nvim's per-feature integration test pattern. If the structure helps catch regressions, split eca-bb's monolithic `integration_test.clj` into per-feature files. Otherwise, document why the monolithic form is fine here.
7. **`bb.edn` auto-discovery** *(optional)*. Skip unless the manual list becomes unwieldy.

## Acceptance criteria

- `state.clj` ≤ 220 LOC (down from 272).
- `view.clj` ≤ 150 LOC; new `view/blocks.clj` ≤ 200 LOC.
- `README.md`, `LICENSE`, `CHANGELOG.md` present and accurate.
- `exit` / `shutdown` lifecycle test added; passes.
- Block-navigation keybindings document and implemented end-to-end (manual smoke).
- `bb test` and `bb itest` green at every commit.
- No behavioural regressions in core chat / approval / picker / login flows.

## Risks

| Risk | Mitigation |
|---|---|
| `view.clj` split touches the rendering hot-path | Phase 5 has thorough unit + integration coverage; rely on it. Visual smoke before / after. |
| Residual notification extraction reopens `chat/opened` echo logic | Step 4 should land after step 3 when render coverage is fresh. |
| Block-navigation keybindings conflict with Phase 5 focus model | Spec the keybindings BEFORE implementing. One-pager listing the full keymap. |
| Integration-test split is busywork | Skip step 6 unless a regression motivates it. |

## Phase B → Phase C

Phase A was structural alignment with ECA conventions. Phase B finishes the structural debt. **Phase C is not currently planned** — once Phase B lands, structural work should be done for the foreseeable future, and all forward motion belongs on the roadmap track.

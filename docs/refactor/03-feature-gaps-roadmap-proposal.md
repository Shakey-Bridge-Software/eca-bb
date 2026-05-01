# Feature Gaps → Roadmap Proposal

The Phase A assessment audited eca-bb against the ECA `development.md` editor-plugin checklist and surfaced gaps. Some are structural (Phase B). Others are user-visible features and belong on the [roadmap](../roadmap.md). This doc proposes how the feature gaps map to existing or new roadmap phases.

## Current roadmap state

Phases 1a–5 complete. Planned but not detailed:

| Phase | Name | Focus |
|---|---|---|
| 6 | Markdown Rendering | Render assistant text as formatted ANSI |
| 7 | Message Steering | Influence a running prompt |
| 8 | Rich Interaction | Server-initiated Q&A |
| 9 | Power Features | Context injection, jobs, rollback/fork |

## Feature gaps from Phase A assessment

| Gap | Source | Size | Recommended home |
|---|---|---|---|
| Markdown rendering for assistant text | development.md "Support reason/thoughts content blocks" + general UX | medium | **Phase 6** (already planned) |
| Tool-call diff rendering for file changes | development.md "Support tools details: showing a file change like a diff" | medium | **New Phase 6.5: Tool-Call Diff Display** |
| MCP UI: status + details panel + server updates | development.md "Show MCPs summary", "Open MCP details window", "Receive MCP server updates" | large | **New Phase 7: MCP Integration** (renumber existing 7 → 8) |
| `chat/queryCommands` autocomplete | development.md "Support chat commands (`/`) auto completion, querying via `chat/queryCommands`" | small | **Extend existing Phase 4** (revisit) — or fold into new Phase 8 |
| `chat/queryContext` add-context UI | development.md "Present and add contexts via `chat/queryContext` request" | medium | **Phase 9** (context injection — already there) |
| In-app stderr / log viewer | development.md "Allow check eca server process stderr for debugging/logs" | small-medium | **New phase or fold into Phase 8 (rich interaction)** |
| Block-navigation keybindings completion | development.md "keybindings: navigate through chat blocks/messages" | small | **Phase B (structural)** — bindings are polish on Phase 5, not a new feature |

## Proposed roadmap (post-Phase B)

```
Phase 1a: Reliable Core              ✅
Phase 1b: Login Hardening            ✅
Phase 2:  Model & Agent Identity     ✅
Phase 3:  Session Continuity         ✅
Phase 4:  Command System             ✅
Phase 5:  Rich Display               ✅
Phase 6:  Markdown Rendering         (planned — keep as-is)
Phase 6.5: Tool-Call Diff Display    NEW
Phase 7:  MCP Integration            NEW (renumber)
Phase 8:  Message Steering           (was 7)
Phase 9:  Server-Driven Interaction  (was 8) — fold queryCommands enrichment + log viewer here
Phase 10: Power Features             (was 9) — keep queryContext + jobs + rollback
```

Alternative: avoid renumbering by keeping 6/7/8/9 as-is and inserting:

```
Phase 6:  Markdown Rendering         (existing)
Phase 6.5: Tool-Call Diff Display    NEW
Phase 6.7: MCP Integration           NEW
Phase 7:  Message Steering           (existing)
Phase 8:  Server-Driven Interaction  (existing — extended scope)
Phase 9:  Power Features             (existing — extended scope)
```

Numbering aside, the work is the same. Renumbering is cleaner; decimal numbers signal "inserted later" — useful but ugly.

## Per-phase notes

### Phase 6.5 / Tool-Call Diff Display

Extends Phase 5's expandable tool blocks. When a tool call modifies a file (`edit_file`, `write_file`, `apply_patch`), the expanded view should render a unified diff with ANSI red/green colouring. Server already provides before/after content via tool-call payloads.

Estimated effort: small-medium. One new renderer in `view/blocks.clj` (after Phase B's view split).

### Phase 7 / MCP Integration (new)

Largest unplanned-feature item. Three sub-deliverables:

1. **Status indicator** — current MCPs (running / failed / pending) in the status bar.
2. **`/mcp` panel** — list MCPs with details (name, status, available tools, last error). New picker-like overlay.
3. **Server update notifications** — handle MCP status-change notifications from ECA and refresh the panel.

New ns: `mcp.clj` (status state, notification handler, `/mcp` command handler). Extends `view.clj` with an MCP panel renderer.

Estimated effort: medium-large. Aligns with ECA ecosystem expectations — sibling editors all have this.

### Phase 9 (formerly 8) / Server-Driven Interaction

Fold in:
- **`chat/queryCommands`** — autocomplete pulls from server (extending Phase 4's local registry).
- **In-app stderr / log viewer** — new `/logs` panel reading the ECA stderr file.
- Existing scope: server-initiated Q&A.

### Phase 10 (formerly 9) / Power Features

`chat/queryContext` add-context UI fits cleanly here alongside the already-planned context injection / jobs / rollback work.

## Recommendation

1. Land Phase B (structural) first — small, defensive, completes the refactor track.
2. Then resume roadmap track at Phase 6 (markdown).
3. Insert new phases per the proposal above.
4. Open a tracking issue mirroring development.md's checklist (per its instruction: *"Create an issue to help track the effort copying and pasting these check box to help track progress, [example](https://github.com/editor-code-assistant/eca/issues/5)"*) — gives ECA maintainers visibility into eca-bb's progress against the standard.

## Open questions for the user

- **Renumber vs decimals?** 6 / 6.5 / 6.7 vs renumber 7/8/9 → 7/8/9/10.
- **MCP scope?** Full panel + details + status, or minimal status-bar indicator first and panel later (split into Phase 7a / 7b)?
- **Log viewer urgency?** Could land much earlier than Phase 9 — useful debugging tool. Worth promoting to Phase 6.8 or similar?

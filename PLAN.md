# eca-bb

Babashka TUI client for ECA (Editor Code Assistant).

## What

Terminal-first AI coding assistant that talks the ECA protocol over stdin/stdout. Uses charm.clj for the TUI. Unlike editor plugins that push context via @mentions, the LLM pulls context via ECA's built-in tools (filesystem, grep, git, shell).

## Status

Step 1 (transport) complete and REPL-verified. Steps 2-3 remain for MVP-0.

## Architecture

```
reader thread → LinkedBlockingQueue → program/cmd (drain batch) → update → view
```

- `server.clj` — spawn ECA process, JSON-RPC Content-Length framing, reader thread + queue
- `protocol.clj` — message constructors, request ID tracking, response correlation
- `state.clj` — Elm state machine (init/update), mode dispatch (TODO)
- `view.clj` — pure rendering: chat, tools, approval, status (TODO)
- `core.clj` — entry point, arg parsing, charm.clj program/run (TODO)

## MVP-0 Scope

- Spawn + initialize + shutdown
- Single chat with streaming text
- Tool calls with approval (y/n)
- Prompt stop (Esc)
- Graceful shutdown + terminal cleanup on crash

## MVP-1 (follow-up)

- Model/agent pickers
- Reasoning blocks
- Trust mode (`--trust`)
- File context (`--file`, `/file`)
- Usage display
- Collapsible tool blocks

## Running

```bash
# Requires bb 1.12.215+
bb run

# Dev REPL
bb nrepl-server
```

## Key Protocol Messages

```
initialize (req)     → {processId, clientInfo, capabilities, workspaceFolders}
initialized (notif)  → {}
chat/prompt (req)    → {chatId?, message, model?, agent?} → {chatId, model, status}
chat/promptStop (notif) → {chatId}
chat/toolCallApprove (notif) → {chatId, toolCallId}
chat/toolCallReject (notif)  → {chatId, toolCallId}
shutdown (req) → null
exit (notif) → {}
```

Content types: `text`, `progress`, `usage`, `toolCallPrepare`, `toolCallRun`, `toolCallRunning`, `toolCalled`, `toolCallRejected`, `reasonStarted`, `reasonText`, `reasonFinished`.

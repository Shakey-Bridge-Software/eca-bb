# eca-bb — Babashka TUI Client for ECA

## Context

ECA is an editor-agnostic AI coding server with a JSON-RPC protocol over stdin/stdout. All current clients are editor plugins. eca-bb is a terminal-first client using Babashka + charm.clj. The LLM pulls context via ECA's built-in tools — user just chats.

**Location**: `/Users/somhairleolaoire/Documents/workspace/oss/eca/eca-bb/`
**Runtime**: bb script only
**Test**: local Mac + Debian VM

## Architecture

```
eca-bb/
  bb.edn
  src/eca_bb/
    core.clj      — entry point, arg parsing, program/run, shutdown hook
    state.clj     — init, update (mode dispatch), Elm state machine
    view.clj      — pure rendering (chat, tools, approval, status)
    server.clj    — spawn process, JSON-RPC framing, reader thread + queue
    protocol.clj  — message constructors + request ID tracking
```

### Server ↔ charm.clj Bridge

A dedicated reader thread parses JSON-RPC frames from server stdout and pushes them onto a `LinkedBlockingQueue`. The charm.clj cmd drains the queue each frame (batch read), so multiple server messages per render cycle — no sluggishness during fast streaming.

```
reader thread → queue → program/cmd (drain batch) → update → view
```

The reader must distinguish:
- **Responses** (have `id`, no `method`) — correlated to pending request callbacks
- **Notifications** (have `method`, no `id`) — queued for charm.clj update loop

### bb.edn

```clojure
{:paths ["src"]
 :deps {de.timokramer/charm.clj {:mvn/version "0.2.69"}}
 :tasks {run {:doc "Run eca-bb"
              :task (exec 'eca-bb.core/-main)}}}
```

No cheshire dep — bb provides `cheshire.core` built-in. Real cheshire (Jackson-based) crashes in bb.

## MVP-0 Scope (get it working)

**In**: spawn + initialize, single chat with streaming, tool calls with approval (y/n), prompt stop (Esc), graceful shutdown, terminal cleanup on crash.

**Out (MVP-1)**: model/agent pickers, reasoning blocks, trust mode, file context CLI, usage display, collapsible tool blocks, `/file` chat command, `--trust` flag.

## Implementation Steps

### Step 1: Scaffold + JSON-RPC transport
- `bb.edn` with charm.clj dep
- `server.clj`: spawn ECA binary (`:err :inherit` for stderr), Content-Length framing, reader thread → queue, `write-message!`, `read-batch!`
- `protocol.clj`: message constructors (initialize, initialized, chat/prompt, toolCallApprove, toolCallReject, promptStop, shutdown, exit) + `next-id!` atom + pending-requests atom for response correlation
- **Verify via REPL**: spawn server, initialize, print welcome, shutdown.

### Step 2: Minimal TUI + chat loop
- `core.clj`: arg parsing, `program/run` wiring, shutdown hook (`\033[?1049l\033[?25h`), manual `exit!` (program/quit-cmd broken in bb)
- `state.clj`: modes (`:connecting`, `:ready`, `:chatting`, `:approving`), init (spawn+initialize), update dispatch, queue drain cmd
- `view.clj`: chat history, streaming text buffer, input area, status line
- Send `chat/prompt` on Enter, handle response (get chatId), stream `chat/contentReceived` type=text, finalize on `progress state=finished`
- Stop prompt on Esc → send `chat/promptStop`
- **Verify**: `bb run`, send message, see streamed response.

### Step 3: Tool calls + approval
- Handle content types: `toolCallPrepare`, `toolCallRun`, `toolCallRunning`, `toolCalled`, `toolCallRejected`
- Render tool blocks (flat — name, status icon, arguments, result)
- `:approving` mode on `manualApproval: true` → y/n keys → send approve/reject
- Default no-op branch for unknown content types (url, hookAction*, metadata, flag)
- **Verify**: ask LLM to read a file, approve it, see result.

## Key Protocol Messages

```
initialize (req)     → {processId, clientInfo:{name:"eca-bb"}, capabilities:{codeAssistant:{chat:true}}, workspaceFolders:[{uri,name}]}
initialized (notif)  → {}
chat/prompt (req)    → {chatId?, message, model?, agent?} → response: {chatId, model, status}
chat/promptStop (notif) → {chatId}
chat/toolCallApprove (notif) → {chatId, toolCallId}
chat/toolCallReject (notif)  → {chatId, toolCallId}
shutdown (req) → null
exit (notif) → {}
```

Content types to handle: `text`, `progress`, `usage`, `toolCallPrepare`, `toolCallRun`, `toolCallRunning`, `toolCalled`, `toolCallRejected`. Ignore others with no-op.

## Verification

1. **REPL**: spawn → initialize → shutdown (no TUI)
2. **TUI**: `bb run` → send "hello" → see streamed response → quit with q
3. **Tools**: ask to read a file → approve → see contents
4. **Stop**: send long prompt → Esc mid-stream → confirm it stops
5. **Cross-env**: test on Mac + Debian VM

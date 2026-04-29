# Phase 5: Rich Display

> **Pre-condition:** Phase 4 complete (`bb test` passes, command system functional).

## Goal

Replace the flat, text-only chat display with a structured, interactive rendering model — collapsible tool blocks, thinking blocks, and nested sub-agent content — matching the level of UX fidelity seen in nvim's ECA buffer.

---

## Background: What Phase 4 Left Behind

| Gap | Current state | Target state |
|-----|---------------|--------------|
| Tool call display | Summary string only | Collapsed 1-liner; expandable to args + output |
| Thinking content | Not stored or shown | Collapsible `▼ Thought` block |
| Sub-agent content | Suppressed via `parentChatId` check | Nested under `eca__spawn_agent` tool call |
| Item focus | None — scroll only | Tab navigation; Enter/Space toggles expand |
| Tool args/output | Discarded after display | Stored in item for deferred inspect |

The `parentChatId` suppression (commit `6cb3696`) is an interim fix. This phase replaces it with the real solution.

---

## What to Build

### 1. Extended item model

Add fields to tool-call items so they carry enough data to render at any expansion level:

```clojure
;; tool-call item — extended
{:type      :tool-call
 :name      "read_file"
 :state     :called        ; :preparing | :run | :running | :called | :rejected
 :summary   "read foo.clj" ; short display string (existing)
 :args-text nil            ; JSON string of arguments — nil until toolCallRun
 :out-text  nil            ; result string — nil until toolCalled
 :expanded? false          ; collapsed by default
 :focused?  false
 :error?    false}

;; thinking item — new type
;; Populated across three events: reasonStarted → reasonText (many) → reasonFinished
{:type      :thinking
 :id        "r1"         ; correlates streamed reasonText events
 :text      ""           ; accumulated; empty until first reasonText arrives
 :status    :thinking    ; :thinking | :thought (set on reasonFinished)
 :expanded? false
 :focused?  false}

;; hook item — new type
{:type      :hook
 :id        "h1"         ; correlates hookActionFinished
 :name      "pre-tool"   ; hook script/name
 :status    :running     ; :running | :ok | :failed
 :out-text  nil          ; populated on hookActionFinished
 :expanded? false
 :focused?  false}

;; tool-call item for eca__spawn_agent — carries sub-agent items
{:type      :tool-call
 :name      "eca__spawn_agent"
 :state     :called
 :summary   "explorer agent"
 :args-text "..."
 :out-text  "..."
 :expanded? false
 :focused?  false
 :sub-items []}             ; populated by sub-agent contentReceived
```

No other item types change shape.

---

### 2. State additions

```clojure
;; Two new top-level keys
:focus-path nil   ; nil = no focus
                  ; [3]   = top-level item at index 3
                  ; [3 1] = sub-item at index 1 of top-level item 3

;; Sub-agent routing table: subagent chat-id → index into :items
;; Populated when toolCallRun arrives with subagentDetails
:subagent-chats {}      ; {"subagent-chat-42" 7}
```

---

### 3. Protocol event changes

#### `toolCallRun` handler (store args, register sub-agent link)

`toolCallRun` arrives when ECA has collected the full argument JSON. Current handler adds args to `:args-text` (already partially done) and must now also register the sub-agent link when `subagentDetails` is present:

```clojure
(let [sub (:subagentDetails params)]
  (when sub
    (update state :subagent-chats assoc (:subagentChatId sub) tool-item-idx)))
```

#### `toolCalled` handler (store output)

Store the result string in the matching tool-call item's `:out-text`. The result may be long; truncate at a reasonable limit (e.g. 8 KB) with a `... [truncated]` suffix to avoid rendering pathological outputs in full.

#### `contentReceived` with `parentChatId` (route to parent, not suppress)

Replace the current one-line suppression with routing:

```clojure
(if-let [parent-idx (get-in state [:subagent-chats (:chatId params)])]
  ;; append to parent tool call's :sub-items
  (update-in state [:items parent-idx :sub-items]
             conj (content->item params))
  ;; no parent registered: normal flow (direct content)
  (handle-content state params))
```

`content->item` is a small helper that converts a `contentReceived` params map to the appropriate item type (`:assistant-text`, `:tool-call`, `:thinking`, etc.).

#### Hook actions — two-event protocol (new)

| Event | Fields | Action |
|-------|--------|--------|
| `hookActionStarted` | `id`, `name` | Create `:hook` item with `:status :running` |
| `hookActionFinished` | `id`, `status` (`"ok"`/`"failed"`), `output` | Find by `:id`, set `:status :ok/:failed`, store `:out-text` |

```clojure
"hookActionStarted"  → conj items {:type :hook :id id :name name
                                    :status :running :out-text nil :expanded? false}
"hookActionFinished" → update item where (= (:id item) id)
                         assoc :status (keyword status)
                               :out-text output
```

---

#### Thinking — three-event protocol (new)

Thinking is **not** a single content event. It arrives as three distinct events identified by a shared `:id`:

| Event | Fields | Action |
|-------|--------|--------|
| `reasonStarted` | `id` | Create `:thinking` item with empty `:text`, `:status :thinking` |
| `reasonText` | `id`, `text` | Find item by `:id`, append `text` to `:text` |
| `reasonFinished` | `id`, `totalTimeMs` | Find item by `:id`, set `:status :thought` |

```clojure
;; reasonStarted
"reasonStarted" → conj items {:type :thinking :id id :text "" :status :thinking
                               :expanded? false}

;; reasonText — find by id, append
"reasonText" → update item where (= (:id item) id)
                 update :text str text

;; reasonFinished — find by id, mark done
"reasonFinished" → update item where (= (:id item) id)
                     assoc :status :thought
```

The header label in the view reflects status: `▸ Thinking…` while `:status :thinking`, `▸ Thought` once `:thought`.

---

### 4. View: collapsed rendering

`render-item-lines` gains collapsed/expanded dispatch for `:tool-call` and `:thinking`. Everything else is unchanged.

**Collapsed tool call (1 line):**
```
[icon] tool-name   summary-text
```
Example:
```
✅ read_file   read src/foo.clj
⏳ eca__spawn_agent   explorer agent  ▸ 3 steps
```
The `▸ N steps` suffix appears only when `:sub-items` is non-empty.

**Expanded tool call:**
```
[icon] tool-name   summary-text  ▾
  ┌─ Arguments ──────────────────┐
  │ {"path": "src/foo.clj"}      │
  └──────────────────────────────┘
  ┌─ Output ─────────────────────┐
  │ (ns eca-bb.core              │
  │   (:require ...))            │
  └──────────────────────────────┘
```
Box drawing is ASCII-safe (`+`/`-`/`|` fallback) in environments where box chars fail. Wrap at `width - 4`.

**Collapsed hook (1 line):**
```
⚡ hook-name   running…
⚡ hook-name   ok
❌ hook-name   failed
```

**Expanded hook:**
```
⚡ hook-name   ok  ▾
  ┌─ Output ─────────────────────┐
  │ hook stdout/stderr here      │
  └──────────────────────────────┘
```
Focusable and expandable same as `:tool-call`. `:hook` items with no output (empty `:out-text`) show no output block when expanded.

**Collapsed thinking (1 line):**
```
▸ Thinking…   (while :status :thinking — live, pulsing label)
▸ Thought     (once :status :thought — static)
```

**Expanded thinking:**
```
▾ Thought
  ...model's reasoning text...
```
Wrapped at `width - 2`. While still streaming (`:status :thinking`), the expanded body updates in place as `reasonText` events append.

**Expanded `eca__spawn_agent` (shows sub-items indented, each individually expandable):**
```
✅ eca__spawn_agent   explorer agent  ▾
  ◆ Here is the codebase overview...
  ✅ read_file   read src/state.clj
  ✅ list_directory   src/eca_bb/
```
Sub-items render their own collapsed form (1 line each) indented by 2 spaces. They are individually focusable and expandable — Tab walks them in render order alongside top-level items. An expanded sub-item adds its args/output block at the same 2-space indent level.

---

### 5. Focus and toggle

**State machine additions:**

| Key | Condition | Effect |
|-----|-----------|--------|
| `Tab` | `:ready` or `:chatting` | Advance `:focus-path` to next focusable item in render order; wrap around |
| `Shift+Tab` | `:ready` or `:chatting` | Reverse |
| `Enter` / `Space` | focused item exists | Toggle `:expanded?` on focused item; rebuild lines |
| `Escape` | focused item exists | Clear focus (`nil`); rebuild lines |

"Focusable" items: `:tool-call`, `:thinking`, and `:hook`, at any nesting depth. `:user` and `:assistant-text` items are skipped.

**Render-order walk.** Tab advances through a flat sequence of focusable item paths built from `:items` on each keypress: top-level focusable items and — when a parent spawn item is expanded — its focusable sub-items, interleaved in the order they would appear on screen. A collapsed spawn block's sub-items are skipped (they're not visible). This keeps Tab navigation consistent with what the user sees.

Example path sequence for a chat with one user message, one `read_file` (top-level), one expanded `eca__spawn_agent` with two sub-tools:
```
[1]        ; read_file
[2]        ; eca__spawn_agent (the spawn block itself)
[2 0]      ; sub-item: read_file inside spawn
[2 1]      ; sub-item: list_directory inside spawn
```
If the spawn block is collapsed, Tab visits only `[1]` and `[2]`.

**`:focus-path` addressing.**  
Toggle and focus indicator both use `get-in`/`update-in` with the path directly:
```clojure
;; toggle expanded? on focused item
(update-in state (into [:items] (interleave (repeat :sub-items) focus-path)) ...)
;; simplified: build the get-in key sequence from focus-path
;; [3]   → [:items 3]
;; [3 1] → [:items 3 :sub-items 1]
```

**Visual indicator:** the first rendered line of the focused item is prefixed with a `›` glyph (or reverse-video if terminal supports it). This replaces the normal icon prefix. Sub-items retain their 2-space indent; the `›` replaces the icon within that indent.

**Scroll follows focus:** when Tab moves focus, adjust `:scroll-offset` so the focused item's first line is visible.

---

### 6. Rendering architecture note

The current model — `chat-lines` is a flat vec rebuilt by `rebuild-chat-lines` after every state change — still works. `render-item-lines` already dispatches per item type; we add collapsed/expanded branches per type. The rebuild is slightly more expensive when items have long outputs, but acceptable.

The truncation limit on `:out-text` (§3) bounds the worst case.

---

## State machine diagram additions

```
:ready / :chatting
  --Tab-->          advance :focus-path in render order; scroll to show
                    (skips sub-items of collapsed spawn blocks)
  --Shift+Tab-->    reverse
  --Enter/Space-->  toggle :expanded? at :focus-path; rebuild lines
                    (expanding a spawn block makes its sub-items Tab-reachable)
  --Escape-->       (focused item) clear :focus-path only; no mode change
```

No new top-level modes. Focus is orthogonal to existing modes.

---

## Tests

### Unit (`bb test`)

#### `state_test.clj` additions

```clojure
;; Tool args stored on toolCallRun
(deftest tool-args-stored-test
  (testing "toolCallRun stores args-text in matching tool-call item"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :tool-call :name "read_file"
                                :state :run :call-id "tc1"
                                :expanded? false :focused? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/toolCallRunning"
                        :params {:chatId "chat1" :callId "tc1"
                                 :arguments "{\"path\":\"foo.clj\"}"}})]
      (is (= "{\"path\":\"foo.clj\"}"
             (get-in s [:items 0 :args-text]))))))

;; Hook item — two-event lifecycle
(deftest hook-item-test
  (testing "hookActionStarted creates :hook item with :running status"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "hookActionStarted" :id "h1" :name "pre-tool"}}})]
      (is (= 1 (count (:items s))))
      (is (= :hook    (:type   (first (:items s)))))
      (is (= "h1"     (:id     (first (:items s)))))
      (is (= :running (:status (first (:items s)))))))

  (testing "hookActionFinished updates status and stores output"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :hook :id "h1" :name "pre-tool"
                                :status :running :out-text nil :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "hookActionFinished" :id "h1"
                                           :status "ok" :output "done"}}})]
      (is (= :ok   (:status   (first (:items s)))))
      (is (= "done" (:out-text (first (:items s))))))))

;; Thinking item — three-event lifecycle
(deftest thinking-item-test
  (testing "reasonStarted creates :thinking item with empty text and :thinking status"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "reasonStarted" :id "r1"}}})]
      (is (= 1 (count (:items s))))
      (is (= :thinking (:type (first (:items s)))))
      (is (= "r1" (:id (first (:items s)))))
      (is (= "" (:text (first (:items s)))))
      (is (= :thinking (:status (first (:items s)))))
      (is (false? (:expanded? (first (:items s)))))))

  (testing "reasonText appends to matching :thinking item"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :thinking :id "r1" :text "" :status :thinking
                                :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "reasonText" :id "r1" :text "I should..."}}})]
      (is (= "I should..." (:text (first (:items s)))))))

  (testing "reasonFinished sets :status to :thought"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :thinking :id "r1" :text "I should..." :status :thinking
                                :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "reasonFinished" :id "r1" :totalTimeMs 1234}}})]
      (is (= :thought (:status (first (:items s))))))))

;; Sub-agent content routed to parent
(deftest subagent-content-routed-test
  (testing "contentReceived with parentChatId is routed to parent tool call sub-items"
    (let [base  (-> (base-state)
                    (assoc :mode :chatting
                           :items [{:type :tool-call :name "eca__spawn_agent"
                                    :call-id "tc1" :state :called
                                    :expanded? false :sub-items []}]
                           :subagent-chats {"sub-42" 0}))
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId       "sub-42"
                                 :parentChatId "chat1"
                                 :role         "assistant"
                                 :content      {:type "text" :text "sub result"}}})]
      (is (= 1 (count (:items s))))
      (is (= 1 (count (get-in s [:items 0 :sub-items])))))))

;; Tab focus navigation
(deftest tab-focus-navigation-test
  (testing "Tab in :ready with tool-call items sets focus-path to first focusable"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :user :text "hi"}
                              {:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? false}])
          [s _] (update-state base (msg/key-press :tab))]
      (is (= [1] (:focus-path s)))))

  (testing "Enter on focused tool-call toggles :expanded?"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0]
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? true}])
          [s _] (update-state base (msg/key-press :enter))]
      (is (true? (get-in s [:items 0 :expanded?])))))

  (testing "Escape clears focus, does not change mode"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0]
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? true}])
          [s _] (update-state base (msg/key-press :escape))]
      (is (nil? (:focus-path s)))
      (is (= :ready (:mode s)))))

  (testing "Tab skips sub-items of a collapsed spawn block"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :tool-call :name "read_file" :state :called
                               :expanded? false :focused? false}
                              {:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? false :focused? false
                               :sub-items [{:type :tool-call :name "list_dir"
                                            :state :called :expanded? false}]}])
          [s1 _] (update-state base (msg/key-press :tab))
          [s2 _] (update-state s1 (msg/key-press :tab))
          [s3 _] (update-state s2 (msg/key-press :tab))]
      ;; focus wraps: [0] → [1] → [0] (spawn collapsed, sub-items skipped)
      (is (= [0] (:focus-path s1)))
      (is (= [1] (:focus-path s2)))
      (is (= [0] (:focus-path s3)))))

  (testing "Tab reaches sub-items when spawn block is expanded"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path nil
                      :items [{:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? true :focused? false
                               :sub-items [{:type :tool-call :name "read_file"
                                            :state :called :expanded? false}]}])
          [s1 _] (update-state base (msg/key-press :tab))
          [s2 _] (update-state s1 (msg/key-press :tab))]
      (is (= [0] (:focus-path s1)))      ; the spawn block itself
      (is (= [0 0] (:focus-path s2)))))  ; sub-item inside it

  (testing "Enter on focused sub-item toggles its :expanded?"
    (let [base (assoc (base-state)
                      :mode :ready
                      :focus-path [0 0]
                      :items [{:type :tool-call :name "eca__spawn_agent" :state :called
                               :expanded? true :focused? false
                               :sub-items [{:type :tool-call :name "read_file"
                                            :state :called :expanded? false}]}])
          [s _] (update-state base (msg/key-press :enter))]
      (is (true? (get-in s [:items 0 :sub-items 0 :expanded?]))))))
```

#### `view_test.clj` additions

```clojure
(deftest render-item-lines-rich-test
  (testing "collapsed tool-call renders 1 line"
    (let [lines (view/render-item-lines
                  {:type :tool-call :state :called :name "read_file"
                   :summary "foo.clj" :expanded? false} 80)]
      (is (= 1 (count lines)))))

  (testing "expanded tool-call with args renders multiple lines"
    (let [lines (view/render-item-lines
                  {:type :tool-call :state :called :name "read_file"
                   :summary "foo.clj" :args-text "{\"path\":\"foo.clj\"}"
                   :out-text "content" :expanded? true} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/includes? % "foo.clj") lines))
      (is (some #(clojure.string/includes? % "content") lines))))

  (testing "collapsed thinking renders 1 line with ▸"
    (let [lines (view/render-item-lines
                  {:type :thinking :text "I should..." :expanded? false} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "▸"))))

  (testing "expanded thinking shows text"
    (let [lines (view/render-item-lines
                  {:type :thinking :text "I should..." :expanded? true} 80)]
      (is (> (count lines) 1))
      (is (some #(clojure.string/includes? % "I should") lines)))))
```

---

## Stopping Criteria

### Automated (`bb test`)

1. `bb test` passes — no regressions from phases 1–4.
2. `toolCallRun` handler stores `:args-text` on matching tool-call item.
3. `toolCalled` handler stores `:out-text` on matching tool-call item.
4. `contentReceived` with `parentChatId` routes to parent `:sub-items` when registered; falls through to normal handling otherwise.
5. `reasonStarted` creates a `:thinking` item with empty `:text` and `:status :thinking`; `reasonText` appends to it; `reasonFinished` sets `:status :thought`.
5a. `hookActionStarted` creates a `:hook` item with `:status :running`; `hookActionFinished` sets `:status :ok/:failed` and stores `:out-text`.
6. Tab in `:ready` mode with focusable items sets `:focus-path` to first focusable item.
7. Tab again advances focus in render order; wraps at end.
8. Escape clears focus, does not change mode.
9. Enter on focused item toggles `:expanded?`; rebuilds lines.
10. Collapsed tool-call renders exactly 1 line.
11. Expanded tool-call with `:args-text` and `:out-text` renders > 1 line, content includes both.
12. Collapsed thinking renders 1 line containing `▸`.
13. Expanded thinking renders > 1 line, content visible.
14. `eca__spawn_agent` expanded with non-empty `:sub-items` renders sub-items indented.
15. Tab skips sub-items of a collapsed spawn block; visiting only top-level focusable items.
16. Tab reaches sub-items when the parent spawn block is expanded.
17. Enter on a focused sub-item (`:focus-path [i j]`) toggles that sub-item's `:expanded?`.

### Integration (`bb itest`)

15. Sending a prompt that invokes a tool — collapsed tool block visible in chat after completion.
16. Tab moves visual focus indicator to tool block.
17. Enter expands block — args and output visible.
18. Enter again collapses block back to 1 line.
19. Escape clears focus indicator.
20. Sending a prompt that invokes `eca__spawn_agent` — after completion, collapsed spawn block shows `▸ N steps` suffix.
21. Expanding spawn block shows sub-agent steps indented.
22. Tab reaches a sub-item inside an expanded spawn block; Enter expands it to show its args/output.

### Manual

22. `bb run` → send a message that reads a file → tool block shows as 1-line collapsed entry with ✅ icon.
23. Tab to focus it → `›` indicator visible on the line.
24. Enter → expands to show arguments and file content output.
25. Enter again → collapses.
26. Escape → focus indicator gone, scrolling and typing work normally.
27. Send a message that uses thinking → `▸ Thinking…` appears while streaming; label changes to `▸ Thought` on completion; Tab + Enter expands to show accumulated reasoning text.
28. Send a message that triggers sub-agent → spawn tool block shows `▸ N steps`; expand to inspect nested tool calls.
29. Tab into the spawn block when expanded → sub-items are individually focusable; Enter on a sub-item expands it to show its args/output indented.

---

## Notes

- **Truncation is mandatory.** `:out-text` must be capped (≤ 8 KB rendered, with `[truncated]` notice) to prevent runaway re-render times on verbatim file dumps. The full output exists on disk in `~/.cache/eca/toolCallOutputs/` if needed.

- **`parentChatId` suppression removed.** The `(if (:parentChatId params) [state nil] ...)` guard in `handle-eca-notification` is replaced by the routing logic in §3. If no parent is registered (race or unknown sub-agent), content falls through to normal handling rather than being silently dropped.

- **Scroll + focus interaction.** Tab advancing focus must ensure the focused item's first line is within the visible window. Adjust `:scroll-offset` if the focused item is outside the current viewport. No animation — jump directly.

- **Syntax highlighting deferred.** Code blocks within tool output are rendered as plain text in this phase. A future phase (post–4.5) can add a lightweight tokenizer for common languages. ANSI colors from ECA pass through unchanged.

- **`subagentDetails` availability.** The `SubagentDetails` struct (`subagentChatId`) is present on `toolCallRun` params per the ECA protocol spec. This is the link that populates `:subagent-chats`. If ECA sends it, routing works automatically; if absent (tool is not a sub-agent spawn), `:subagent-chats` is not updated and `parentChatId` content falls through.

- **No new modes.** Focus state is orthogonal to `:ready`/`:chatting`/`:approving`. Tab and Enter remain active in both `:ready` and `:chatting` (so the user can inspect previous tool blocks while the agent is working).

- **Content type inventory.** Reference from eca-webview's protocol. Columns: handled in eca-bb today / handled in this phase / deferred.

  | Content type | Today | Phase 4.5 | Deferred |
  |---|---|---|---|
  | `text` (assistant) | ✅ | — | — |
  | `toolCallPrepare` | ✅ | — | — |
  | `toolCallRun` | ✅ | store `:args-text` | — |
  | `toolCallRunning` | ✅ | — | — |
  | `toolCalled` | ✅ | store `:out-text` | — |
  | `toolCallRejected` | ✅ | — | — |
  | `reasonStarted` | ❌ | ✅ | — |
  | `reasonText` | ❌ | ✅ | — |
  | `reasonFinished` | ❌ | ✅ | — |
  | `hookActionStarted` | ❌ | ✅ | — |
  | `hookActionFinished` | ❌ | ✅ | — |
  | `metadata` (chat title) | ✅ (via `chat/opened`) | — | — |
  | `progress` | ✅ (via `$/progress`) | — | — |
  | `flag` | ❌ | ❌ | phase 9 |
  | `url` | ❌ | ❌ | phase 6 |
  | `usage` | ✅ (status bar) | — | — |

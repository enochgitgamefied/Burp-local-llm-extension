# Burp-Local-Agent 0.2.2

A standalone Java Burp Suite extension adapting Arken-Local's **passive HTTP review** workflow. Right-click captured traffic, queue it, edit the prompt, and send it to an OpenAI-compatible model running on your computer. Arken Proxy, its Python backend, Node.js, and a cloud API key are not required.

## Install

1. In Burp Suite, open **Extensions → Installed → Add**.
2. Choose **Java**, select `Burp-Local-Agent.jar`, and click **Next**.
3. Open the **Burp-Local-Agent → Settings** tab.
4. Start a local model server and select its preset:
   - Ollama: `http://localhost:11434/v1`
   - LM Studio: `http://localhost:1234/v1` (start its OpenAI-compatible server)
   - Custom: another OpenAI-compatible server on localhost.
5. Click **Test connection / list models**, choose a loaded chat model, adjust context/output limits to the model's actual capacity, and **Save settings**.
6. Right-click a request in a Burp HTTP table or request/response editor and choose **Send to Burp-Local-Agent**.
7. Open the extension's **Agent** tab, select the queued exchange, inspect/edit its prompt, and click **Send**. Enter follow-up questions in the same prompt editor.

The extension uses the Montoya API, compiled against version 2026.7 with Java 17 bytecode. Use a recent Burp release supporting this API. Both table selections and editor context menus are supported; WebSocket messages and Scanner issue selections have no dedicated integration. The context menu queues whole exchanges, including the response if present, rather than only highlighted text.

## Included features

- Multiple selected exchanges enter an editable queue; sending is explicit and sequential.
- Centered conversation with user bubbles, formatted agent replies, and copy controls.
- Markdown headings, emphasis, lists, tables, and fenced code; long messages can be expanded.
- Collapsible review queue, compact composer, model indicator, and a working-state message.
- Enter sends; Shift+Enter adds a new line. Import/export lives in the top-right ••• menu.
- Light/dark conversation colors selected from Burp's theme, with follow-up conversation context.
- Ollama / LM Studio presets, editable model names, model discovery, and connection testing.
- Persisted URL/model/context/output settings; API keys remain in memory only.
- Optional credential/cookie header redaction, enabled by default; long low-signal headers are shortened.
- Cookie names and Set-Cookie attributes, including SameSite values, remain visible.
- Oversized captures are reviewed in chunks and combined into a final response. No accepted chunks are silently dropped.
- Cancellation, connection/read timeouts, clear errors, one retry for reasoning-only responses, and an output-limit indicator.
- JSON chat export and reopen. A new load starts with an empty chat and queue.

## Scope and differences from Arken

This is an initial passive-review port, **not full feature parity** with Arken-Local. The React UI and FastAPI/SQLite backend were replaced with a Java extension, native UI, and direct local HTTP client.

| Arken feature | This extension |
| --- | --- |
| Context-menu agent handoff | Burp table/editor context menus → local queue |
| Queue and editable prompt | Included; memory only, one item sent at a time |
| Local settings and model test | Included; loopback endpoints only |
| HTTP preprocessing | Adapted, preserving cookie attributes and avoiding body-as-header redaction |
| Context budgets and chunk synthesis | Included; hierarchical summary reduction |
| Follow-up chat and history | Included; explicit export/reopen rather than a SQLite history drawer |
| Streaming tokens | Complete replies with progress status; token streaming is not included |
| Continue / edit-resend | Type a follow-up or edit/re-send a queued draft; no dedicated reply-edit buttons |
| Cloud consultation, multi-agent routing | Not included |
| Nmap, Metasploit, scanner/tool loops | Not included; no target requests or command execution |
| Training validation, save-analysis, debug-log API | Not included |
| Domain privacy cleanup | No automatic domain rewriting; edit the preview if needed |

The system prompt requests evidence-based defensive observations and remediation. The extension has no tool execution or replay mechanism. Model output uses a display-only Markdown subset. Raw HTML is escaped, and image/link markup never loads external resources. Copy and export preserve the original text.

## Data and limits

Only the configured local server receives model traffic. Allowed hostnames are `localhost`, `127.0.0.1`, and `[::1]`. HTTP redirects and system HTTP proxies are disabled. A local server can itself forward data elsewhere; its configuration remains under your control.

Header redaction does **not** scrub URL parameters, request/response bodies, arbitrary custom headers, or text entered manually. Review the exact queued prompt. Changing the redaction checkbox affects newly queued exchanges. Exported chat JSON contains the displayed prompts and replies. Closing/unloading the extension discards unexported chats and queued drafts.

Each queued exchange is limited to 2 MiB and the queue to 200 items. Chunking is limited to 64 initial chunks; oversized inputs fail explicitly. Estimates use a conservative UTF-8 byte heuristic, not the model's tokenizer; actual context use can differ. Older conversation pairs may be omitted to fit a normal follow-up. Oversized-prompt chunk analysis uses that prompt alone and summarizes evidence, so full cross-chunk context is not guaranteed. Progress indicates these modes.

Connections time out after 5 seconds. Model discovery has a 15-second read timeout. Model-response reads default to 600 seconds and can be adjusted under Settings → Model response timeout (seconds), from 1 to 3600 seconds. This is the maximum wait for response data per blocking read, not a total duration limit for a multi-chunk review. Increase the model output limit or disable reasoning in the server if a model returns reasoning without a final answer. The extension does not change model-server reasoning settings. It uses `/models` and non-streaming `/chat/completions` with `max_tokens`.

## Build and verification

Requirements: JDK 17+, Bash, curl, Python 3, and internet access for the first dependency download. Maven is not required.

```sh
./build.sh
```

This downloads pinned Montoya/Gson artifacts from Maven Central, verifies their SHA-256 checksums, compiles the source, runs the test harness, and creates `build/Burp-Local-Agent.jar`. Montoya is compile-only; Gson is bundled. Build dependencies and intermediate files live under `work/`.

The harness checks HTTP payloads and model discovery against a loopback mock server, header preprocessing, Unicode chunk preservation, context limits, synthesis, malformed/empty/error responses, redirects, reasoning retries, cancellation, and Montoya tab/context-menu registration using test doubles. It also renders a headless UI preview to `work/ui-preview.png`.

At delivery: **95 checks passed** and synthetic empty, conversation, narrow, working, and dark-theme views were rendered for visual inspection. This redesigned JAR has **not** been loaded into a live Burp session, and no real Ollama/LM Studio inference or target testing was performed. A live load and one benign captured exchange remain the runtime acceptance check.

## Source mapping

The original project remains unchanged outside this new `burp-local-agent/` directory. Reference implementation:

- `src/pages/ArkenAgent.jsx`: local settings, model discovery, queued prompt/chat flow.
- `backend/api/routers/chat_router.py`: `_preprocess_http_for_local`, `_split_local_chunks`, `_local_chat_once`, and local chunk synthesis.
- `src/pages/Proxy.jsx`, `Logger.jsx`, `Repeater.jsx`: context-menu handoff pattern.

Extension source: `src/main/java/localagent/` contains the Burp entry point, UI, model client, and context preprocessing. Tests are in `src/test/java/localagent/AgentTests.java`.

API references: [PortSwigger extension tutorial](https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating/first-extension), [Montoya context-menu API](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/ui/contextmenu/ContextMenuEvent.html).

Gson's Apache 2.0 license and attribution are included under `src/main/resources/META-INF/` and in the built JAR. No new license is asserted for the user's Arken-derived code.

## Updating from 0.1.0

Export any chat you want to keep before unloading the previous extension. Remove/unload the old extension in Burp, then add the updated JAR. Reopen your exported chat through the **••• → Open chat…** menu.

For synthetic UI previews after building:

```sh
java -Djava.awt.headless=true -cp 'work/lib/*:work/classes:work/test-classes' localagent.UiPreview
```

## 0.2.1 composer fix

Send clears the composer immediately while the submitted message appears in the conversation. A failed or stopped request restores the exact edited draft; a successful request keeps the composer empty and removes its queued item. Twelve loopback integration checks cover these in-flight, success, failure, and Stop transitions.

## 0.2.2 timeout handling

Replaces the fixed three-minute model-response read timeout with a persisted setting defaulting to ten minutes. Old saved settings automatically use the new default. Connection, model discovery, and response timeouts have distinct messages. Timeouts preserve the draft and never automatically resubmit the request. Six loopback checks cover the default, validation, an actual short read timeout, delayed success with a larger budget, and absence of automatic retries. This does not diagnose model-server errors; inspect the local server logs if generation still does not complete.

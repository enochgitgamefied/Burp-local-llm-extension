package localagent;

import burp.api.montoya.*;
import burp.api.montoya.ui.contextmenu.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static localagent.ContextPipeline.*;

public final class AgentTests {
    static int checks;
    static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    interface Throwing { void run() throws Exception; }
    static void fails(Throwing action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError(label);
    }
    static LocalClient.Settings settings(String base) { return new LocalClient.Settings(base, "test-model", "test-key", 4096, 512); }
    static Object proxy(Class<?> type, java.util.function.BiFunction<Method, Object[], Object> handler) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p,m,a) -> handler.apply(m,a));
    }
    public static void main(String[] args) throws Exception {
        String input = "--- REQUEST ---\nGET / HTTP/1.1\nCookie: sid=secret; pref=blue\nAuthorization: Bearer secret\nX-Api-Key: secret\n\nCookie: body literal\n--- RESPONSE ---\nHTTP/1.1 200 OK\nSet-Cookie: sid=secret; Secure; HttpOnly; SameSite=Lax; Path=/\n\nhello";
        String result = preprocess(input, true);
        check(!result.contains("secret"), "redacts credentials");
        check(result.contains("SameSite=Lax; Path=/"), "preserves cookie attribute values");
        check(result.contains("Cookie: body literal"), "does not treat body as headers");
        check(preprocess(input, false).contains("sid=secret"), "optional header redaction");
        String unicode = "abc🙂汉字\n".repeat(1200);
        List<String> chunks = chunks(unicode, 600);
        check(String.join("", chunks).equals(unicode), "chunks retain every character");
        check(chunks.stream().allMatch(c -> estimate(c) <= 600), "unicode chunks fit estimated budget");
        fails(() -> settings("https://example.com/v1"), "reject remote host");
        fails(() -> settings("http://localhost:11434/v1?x=1"), "reject URL query");
        fails(() -> new LocalClient.Settings("http://localhost/v1", "x", "", 2048, 2048), "reject invalid budget");

        String markdown = Markdown.render("## Review\n**Important** and `Cache-Control`\n\n| Header | Value |\n| --- | --- |\n| Cache-Control | no-store |\n\n```http\nX-Test: value\n```");
        check(markdown.contains("<h2>Review</h2>"), "renders Markdown headings");
        check(markdown.contains("<b>Important</b>") && markdown.contains("<code>Cache-Control</code>"), "renders inline formatting");
        check(markdown.contains("<table") && markdown.contains("<th>Header</th>") && markdown.contains("<td>no-store</td>"), "renders tables");
        check(markdown.contains("<p class='codeblock'>X-Test:&nbsp;value"), "renders fenced code");
        String untrusted = Markdown.render("<img src='https://example.invalid/image'>\n<script>doSomething()</script>\n[link](https://example.invalid)\n![image](file:///tmp/example)");
        check(!untrusted.contains("<img") && !untrusted.contains("<script") && !untrusted.contains("<a "), "model HTML and resource URLs stay inert");
        check(Markdown.render("```\n**literal**\n").contains("**literal**"), "unfinished code fences preserve literal text");

        AtomicInteger mode = new AtomicInteger(), calls = new AtomicInteger();
        List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch slowStarted = new CountDownLatch(1), slowRelease = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverPool = Executors.newCachedThreadPool(); server.setExecutor(serverPool);
        server.createContext("/v1/models", ex -> respond(ex, 200, "{\"data\":[{\"id\":\"embedding-model\"},{\"id\":\"test-model\"}]}"));
        server.createContext("/v1/chat/completions", ex -> {
            try {
                check("Bearer test-key".equals(ex.getRequestHeaders().getFirst("Authorization")), "API key sent to local endpoint");
                JsonObject request = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                requests.add(request); int call = calls.incrementAndGet();
                int currentMode = mode.get();
                if (currentMode == 1) { respond(ex, 500, "server error"); return; }
                if (currentMode == 2) { respond(ex, 200, "not JSON"); return; }
                if (currentMode == 3 && call == 1) { respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"reasoning\"}}]}"); return; }
                if (currentMode == 4) { slowStarted.countDown(); slowRelease.await(10, TimeUnit.SECONDS); }
                if (currentMode == 5) { respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"); return; }
                if (currentMode == 6) { ex.getResponseHeaders().set("Location", "http://example.com"); respond(ex, 302, ""); return; }
                respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"Captured response reviewed.\"},\"finish_reason\":\"stop\"}]}");
            } catch (Exception e) { ex.close(); }
        });
        server.start(); String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try {
            try (LocalClient client = new LocalClient(settings(base))) {
                check(client.models().equals(List.of("test-model")), "model discovery filters embeddings");
                check(client.analyze(List.of(), "Summarize capture", s -> {}).contains("reviewed"), "chat response parsed");
                JsonObject payload = requests.get(0);
                check(!payload.get("stream").getAsBoolean(), "compatible JSON completion request");
                check(payload.get("model").getAsString().equals("test-model"), "selected model used");
                calls.set(0); mode.set(3);
                check(client.analyze(List.of(), "Summarize capture", s -> {}).contains("reviewed") && calls.get() == 2, "reasoning-only retried once");
                mode.set(0); calls.set(0); requests.clear();
                String longCapture = "--- REQUEST ---\nGET / HTTP/1.1\n\n" + "sample captured text ".repeat(1100);
                client.analyze(List.of(), longCapture, s -> {});
                check(calls.get() > 2, "large capture chunked and synthesized");
                String allPrompts = requests.toString();
                check(allPrompts.contains("Synthesize a passive review"), "final synthesis called");
                for (JsonObject request : requests) {
                    Message[] sent = new Gson().fromJson(request.get("messages"), Message[].class);
                    check(estimate(Arrays.asList(sent)) + request.get("max_tokens").getAsInt() <= 4096, "each request within estimated budget");
                }
                mode.set(1); fails(() -> client.analyze(List.of(), "capture", s -> {}), "HTTP errors surfaced");
                mode.set(2); fails(() -> client.analyze(List.of(), "capture", s -> {}), "invalid JSON surfaced");
                mode.set(5); fails(() -> client.analyze(List.of(), "capture", s -> {}), "empty answer surfaced");
                mode.set(6); fails(() -> client.analyze(List.of(), "capture", s -> {}), "redirect not followed");
            }
            mode.set(4);
            LocalClient slow = new LocalClient(settings(base));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> running = worker.submit(() -> { try { slow.analyze(List.of(), "capture", s -> {}); } catch (Exception ignored) {} });
            check(slowStarted.await(5, TimeUnit.SECONDS), "slow request started");
            running.cancel(true); slow.close(); slowRelease.countDown(); worker.shutdownNow();
            check(worker.awaitTermination(5, TimeUnit.SECONDS), "cancellation releases worker");
        } finally { slowRelease.countDown(); server.stop(0); serverPool.shutdownNow(); }
        AtomicReference<ContextMenuItemsProvider> provider = new AtomicReference<>();
        AtomicReference<AgentPanel> panel = new AtomicReference<>();
        AtomicReference<Runnable> unload = new AtomicReference<>();
        MontoyaApi api = (MontoyaApi) proxy(MontoyaApi.class, (method, arguments) -> proxy(method.getReturnType(), (m, a) -> {
            if (m.getName().equals("registerContextMenuItemsProvider")) provider.set((ContextMenuItemsProvider) a[0]);
            if (m.getName().equals("registerSuiteTab")) panel.set((AgentPanel) a[1]);
            if (m.getName().equals("registerUnloadingHandler")) unload.set(() -> ((burp.api.montoya.extension.ExtensionUnloadingHandler) a[0]).extensionUnloaded());
            if (m.getName().equals("preferences")) return proxy(m.getReturnType(), (pm, pa) -> null);
            return null;
        }));
        new BurpLocalAgent().initialize(api);
        check(provider.get() != null && panel.get() != null, "extension registers tab and context menu");
        ContextMenuEvent empty = (ContextMenuEvent) proxy(ContextMenuEvent.class, (m, a) -> m.getName().equals("selectedRequestResponses") ? List.of() : Optional.empty());
        check(provider.get().provideMenuItems(empty).isEmpty(), "no menu without selected HTTP data");
        SwingUtilities.invokeAndWait(() -> {
            panel.get().enqueue("GET https://example.invalid/", input);
            check(panel.get().queue.size() == 1, "capture enters queue");
            check(panel.get().draft.getText().contains("sid=[redacted]"), "preview is preprocessed before sending");
            panel.get().draft.setText("edited prompt");
            check(panel.get().queue.get(0).draft.equals("edited prompt"), "queue retains edits");
            var requestType = burp.api.montoya.http.message.requests.HttpRequest.class;
            Object request = proxy(requestType, (m, a) -> switch (m.getName()) {
                case "method" -> "GET";
                case "url" -> "https://example.invalid/";
                case "toString" -> "GET / HTTP/1.1\r\nHost: example.invalid\r\n\r\n";
                case "toByteArray" -> proxy(burp.api.montoya.core.ByteArray.class, (bm, ba) -> 48);
                default -> null;
            });
            Object exchange = proxy(burp.api.montoya.http.message.HttpRequestResponse.class,
                (m, a) -> m.getName().equals("request") ? request : null);
            ContextMenuEvent table = (ContextMenuEvent) proxy(ContextMenuEvent.class,
                (m, a) -> m.getName().equals("selectedRequestResponses") ? List.of(exchange) : Optional.empty());
            ((JMenuItem) provider.get().provideMenuItems(table).get(0)).doClick();
            check(panel.get().queue.size() == 2, "table context action queues request-only capture");
            Object editor = proxy(MessageEditorHttpRequestResponse.class, (m, a) -> exchange);
            ContextMenuEvent editorEvent = (ContextMenuEvent) proxy(ContextMenuEvent.class,
                (m, a) -> m.getName().equals("selectedRequestResponses") ? List.of() : Optional.of(editor));
            ((JMenuItem) provider.get().provideMenuItems(editorEvent).get(0)).doClick();
            check(panel.get().queue.size() == 3, "editor context action queues exchange");
            panel.get().queueList.setSelectedIndex(1);
            panel.get().setSize(1280, 800);
            layout(panel.get());
            var image = new java.awt.image.BufferedImage(1280, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); panel.get().printAll(graphics); graphics.dispose();
            try { javax.imageio.ImageIO.write(image, "png", new java.io.File("work/ui-preview.png")); }
            catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            unload.get().run();
        });
        System.out.println("PASS: " + checks + " checks");
    }
    static void layout(java.awt.Container component) {
        component.doLayout();
        for (java.awt.Component child : component.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
    static void respond(HttpExchange ex, int status, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length); try (var out = ex.getResponseBody()) { out.write(bytes); }
    }
}

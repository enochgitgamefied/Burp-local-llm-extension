package localagent;

import burp.api.montoya.MontoyaApi;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import javax.swing.*;
import java.net.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Hold actual loopback responses open to verify the composer during in-flight requests. */
public final class ComposerTests {
    static int checks;
    static AgentPanel panel;
    static final class Reply {
        final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        final int status;
        volatile String prompt;
        Reply(int status) { this.status = status; }
    }
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static Object field(String name) throws Exception {
        Field f = AgentPanel.class.getDeclaredField(name); f.setAccessible(true); return f.get(panel);
    }
    static void send() { panel.draft.getActionMap().get("send-message").actionPerformed(null); }
    static void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicBoolean ready = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready.set(panel.draft.isEditable()));
            if (ready.get()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Composer did not return to idle");
    }
    public static void main(String[] args) throws Exception {
        AtomicReference<Reply> response = new AtomicReference<>(new Reply(200));
        ExecutorService serverWorkers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/v1/chat/completions", exchange -> {
            Reply reply = response.get();
            try {
                var json = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var messages = json.getAsJsonArray("messages");
                reply.prompt = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
                reply.arrived.countDown(); reply.release.await(10, TimeUnit.SECONDS);
                AgentTests.respond(exchange, reply.status, "{\"choices\":[{\"message\":{\"content\":\"Captured response reviewed.\"}}]}");
            } catch (Exception ignored) { exchange.close(); }
        });
        server.start();
        try {
            SwingUtilities.invokeAndWait(() -> {
                MontoyaApi api = (MontoyaApi) AgentTests.proxy(MontoyaApi.class, (method, arguments) -> AgentTests.proxy(method.getReturnType(), (m, a) -> {
                    if (m.getName().equals("preferences")) return AgentTests.proxy(m.getReturnType(), (pm, pa) -> null);
                    return null;
                }));
                panel = new AgentPanel(api);
                try { ((JTextField) field("base")).setText("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); }
                catch (Exception ex) { throw new RuntimeException(ex); }
                panel.enqueue("GET example.invalid", "--- REQUEST ---\nGET / HTTP/1.1\n\n");
                panel.draft.setText("My edited capture review"); send();
                check(panel.draft.getText().isEmpty(), "composer clears immediately on Send");
                check(panel.queue.get(0).draft.equals("My edited capture review"), "clearing composer preserves edited queued prompt");
                check(!panel.draft.isEditable(), "in-flight composer is disabled");
            });
            Reply first = response.get();
            check(first.arrived.await(5, TimeUnit.SECONDS), "local server received request");
            check("My edited capture review".equals(first.prompt), "server receives submitted text despite cleared composer");
            first.release.countDown(); idle();
            SwingUtilities.invokeAndWait(() -> {
                check(panel.draft.getText().isEmpty() && panel.queue.isEmpty(), "success leaves composer empty and removes sent queue item");
            });
            Reply failure = new Reply(500); response.set(failure);
            SwingUtilities.invokeAndWait(() -> {
                panel.enqueue("GET example.invalid/retry", "--- REQUEST ---\nGET /retry HTTP/1.1\n\n");
                panel.draft.setText("Edited request to retry"); send();
            });
            check(failure.arrived.await(5, TimeUnit.SECONDS), "failure scenario started");
            failure.release.countDown(); idle();
            SwingUtilities.invokeAndWait(() -> {
                check(panel.draft.getText().equals("Edited request to retry"), "failure restores submitted draft");
                check(panel.queue.get(0).draft.equals("Edited request to retry"), "failure retains queue edits");
            });
            Reply stopped = new Reply(200); response.set(stopped);
            SwingUtilities.invokeAndWait(ComposerTests::send);
            check(stopped.arrived.await(5, TimeUnit.SECONDS), "retry starts");
            SwingUtilities.invokeAndWait(() -> {
                check(panel.draft.getText().isEmpty(), "retry also clears composer");
                try { ((JButton) field("cancel")).doClick(); } catch (Exception ex) { throw new RuntimeException(ex); }
                check(panel.draft.getText().equals("Edited request to retry") && panel.draft.isEditable(), "Stop restores editable submitted draft");
            });
            stopped.release.countDown();
        } finally {
            response.get().release.countDown();
            if (panel != null) SwingUtilities.invokeAndWait(panel::shutdown);
            server.stop(0); serverWorkers.shutdownNow();
        }
        System.out.println("PASS: " + checks + " composer lifecycle checks");
    }
}

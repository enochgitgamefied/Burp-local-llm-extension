package localagent;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TimeoutTests {
    static int checks;
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(new LocalClient.Settings("http://localhost/v1", "test", "", 4096, 512).responseTimeoutSeconds() == 600, "default model timeout is ten minutes");
        try { new LocalClient.Settings("http://localhost/v1", "test", "", 4096, 512, 0); throw new AssertionError("zero timeout accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        AtomicInteger calls = new AtomicInteger();
        ExecutorService workers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
        server.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            try {
                ex.getRequestBody().readAllBytes();
                Thread.sleep(1800);
                AgentTests.respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"Delayed response\"}}]}");
            } catch (Exception ignored) { ex.close(); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try {
            long start = System.nanoTime();
            try (LocalClient client = new LocalClient(new LocalClient.Settings(url, "test", "", 4096, 512, 1))) {
                try { client.analyze(List.of(), "Summarize supplied capture", s -> {}); throw new AssertionError("request did not time out"); }
                catch (IOException expected) {
                    check(expected.getMessage().contains("1 seconds") && expected.getMessage().contains("Settings"), "timeout identifies configured duration and remedy");
                    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1700, "configured short timeout is enforced");
                }
            }
            try (LocalClient client = new LocalClient(new LocalClient.Settings(url, "test", "", 4096, 512, 4))) {
                check(client.analyze(List.of(), "Summarize supplied capture", s -> {}).equals("Delayed response"), "longer setting allows delayed generation to complete");
            }
            check(calls.get() == 2, "timed-out generation is not automatically resubmitted");
        } finally { server.stop(0); workers.shutdownNow(); }
        System.out.println("PASS: " + checks + " timeout checks");
    }
}

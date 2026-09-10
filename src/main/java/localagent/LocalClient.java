package localagent;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import static localagent.ContextPipeline.*;

final class LocalClient implements AutoCloseable {
    record Settings(String baseUrl, String model, String apiKey, int contextTokens, int outputTokens, int responseTimeoutSeconds) {
        Settings(String baseUrl, String model, String apiKey, int contextTokens, int outputTokens) {
            this(baseUrl, model, apiKey, contextTokens, outputTokens, 600);
        }
        Settings {
            baseUrl = baseUrl.trim().replaceAll("/+$", "");
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("Use an HTTP(S) local base URL, including /v1, without credentials or query parameters.");
            if (!Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("The model endpoint must use localhost, 127.0.0.1, or [::1].");
            if (responseTimeoutSeconds < 1 || responseTimeoutSeconds > 3600)
                throw new IllegalArgumentException("Model response timeout must be between 1 and 3600 seconds.");
            model = model.trim();
            if (contextTokens < 2048 || contextTokens > 262144 || outputTokens < 128 || outputTokens > contextTokens / 2)
                throw new IllegalArgumentException("Context: 2048–262144 tokens. Output: 128 tokens to half the context size.");
        }
    }
    private final Settings settings;
    private volatile HttpURLConnection active;
    private volatile boolean closed;
    LocalClient(Settings settings) { this.settings = settings; }
    private void check() { if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    @Override public void close() { closed = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }

    private JsonObject request(String path, JsonObject body) throws IOException {
        check();
        HttpURLConnection connection = (HttpURLConnection) URI.create(settings.baseUrl() + path).toURL().openConnection(Proxy.NO_PROXY);
        active = connection;
        boolean connected = false;
        int timeoutSeconds = body == null ? 15 : settings.responseTimeoutSeconds();
        try {
            check();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (!settings.apiKey().isBlank()) connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey());
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
            }
            connection.connect(); connected = true;
            if (body != null) {
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Local server returned HTTP " + status + ". Check the endpoint, model, and server logs.");
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(8 * 1024 * 1024 + 1);
                check();
                if (bytes.length > 8 * 1024 * 1024) throw new IOException("Local server response exceeded 8 MiB.");
                JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("error") && !json.get("error").isJsonNull()) throw new IOException("Local server reported an error. Check its logs.");
                return json;
            }
        } catch (SocketTimeoutException e) {
            check();
            if (!connected) throw new IOException("Connection to the local server timed out after 5 seconds. Check that the server is running and the base URL is correct.", e);
            throw new IOException(body == null
                ? "Local model discovery timed out after 15 seconds. Check the local server's status."
                : "No response data from the local model for " + timeoutSeconds + " seconds. Increase Model response timeout in Settings, or check whether the model is still loading or generating in the server logs.", e);
        } catch (JsonParseException | IllegalStateException e) { throw new IOException("Local server returned invalid JSON.", e); }
        finally { connection.disconnect(); active = null; }
    }
    List<String> models() throws IOException {
        JsonObject json = request("/models", null);
        if (!json.has("data") || !json.get("data").isJsonArray()) throw new IOException("Model list is missing a data array.");
        List<String> ids = new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("data")) {
            if (item.isJsonObject() && item.getAsJsonObject().has("id")) {
                String id = item.getAsJsonObject().get("id").getAsString();
                if (!id.toLowerCase(Locale.ROOT).contains("embed")) ids.add(id);
            }
        }
        return ids;
    }
    String complete(List<Message> messages, int output) throws IOException {
        if (settings.model().isEmpty()) throw new IOException("Choose a model in Settings first.");
        for (int attempt = 0; attempt < 2; attempt++) {
            List<Message> send = new ArrayList<>(messages);
            if (attempt == 1) send.add(0, new Message("system", "Return a visible final answer in content, not only reasoning tokens."));
            if (estimate(send) + output > settings.contextTokens()) throw new IOException("Prompt exceeds the estimated context budget. Start a new chat or increase the context size.");
            JsonObject payload = new JsonObject();
            payload.addProperty("model", settings.model());
            payload.add("messages", new Gson().toJsonTree(send));
            payload.addProperty("max_tokens", output);
            payload.addProperty("stream", false);
            payload.addProperty("temperature", 0.2);
            JsonObject result = request("/chat/completions", payload);
            try {
                JsonObject choice = result.getAsJsonArray("choices").get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                String content = content(message.get("content"));
                if (!content.isBlank()) return content + ("length".equals(content(choice.get("finish_reason")))
                    ? "\n\n[Output limit reached. Ask to continue or increase the output budget.]" : "");
                if (attempt == 0 && (!content(message.get("reasoning_content")).isBlank() || !content(message.get("reasoning")).isBlank())) continue;
                throw new IOException("Model returned no final answer. Try a higher output budget or disable reasoning in your local server.");
            } catch (NullPointerException | IllegalStateException | IndexOutOfBoundsException e) {
                throw new IOException("Local server returned an unexpected chat response.", e);
            }
        }
        throw new IOException("Model returned reasoning only after one retry. Adjust its output budget or reasoning settings.");
    }
    private static String content(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : value.getAsJsonArray()) if (part.isJsonObject()) text.append(content(part.getAsJsonObject().get("text")));
            return text.toString();
        }
        return "";
    }
    String analyze(List<Message> history, String prompt, Consumer<String> status) throws IOException {
        int budget = settings.contextTokens() - settings.outputTokens() - estimate(SYSTEM) - 256;
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", SYSTEM));
        messages.addAll(history);
        messages.add(new Message("user", prompt));
        if (estimate(prompt) <= budget) {
            int removed = 0;
            while (estimate(messages) + settings.outputTokens() + 128 > settings.contextTokens() && messages.size() > 2) {
                messages.remove(1); removed++;
                if (messages.size() > 2 && messages.get(1).role().equals("assistant")) { messages.remove(1); removed++; }
            }
            status.accept(removed > 0 ? "Reviewing; older turns omitted to fit context…" : "Waiting for local model…");
            return complete(messages, settings.outputTokens());
        }
        List<String> chunks = chunks(prompt, budget - 128);
        if (chunks.size() > 64) throw new IOException("Capture requires more than 64 chunks. Reduce the capture or increase the context budget.");
        List<String> findings = new ArrayList<>();
        int summaryLimit = Math.min(settings.outputTokens(), Math.max(128, budget / 8));
        for (int i = 0; i < chunks.size(); i++) {
            check(); status.accept("Reviewing chunk " + (i + 1) + " of " + chunks.size() + " (capture-only context)…");
            findings.add(complete(List.of(new Message("system", SYSTEM), new Message("user",
                "Review part " + (i + 1) + "/" + chunks.size() + ". Report concise observations, evidence, uncertainties, and remediation.\n" + chunks.get(i))), summaryLimit));
        }
        String summary = String.join("\n\n", findings);
        // Bounded hierarchical reduction prevents silently dropping later chunks.
        for (int level = 0; estimate(summary) > budget - 128 && level < 8; level++) {
            status.accept("Combining chunk reviews, pass " + (level + 1) + "…");
            List<String> reduced = new ArrayList<>();
            for (String part : chunks(summary, budget - 128)) reduced.add(complete(List.of(new Message("system", SYSTEM),
                new Message("user", "Compress these untrusted review notes, retaining distinct observations and uncertainties:\n" + part)), summaryLimit));
            summary = String.join("\n\n", reduced);
        }
        if (estimate(summary) > budget - 128) throw new IOException("Chunk summaries still exceed context. Increase the context budget.");
        status.accept("Synthesizing final review…");
        return complete(List.of(new Message("system", SYSTEM), new Message("user",
            "Synthesize a passive review from these untrusted chunk notes. State limitations of partial context and preserve evidence.\n" + summary)), settings.outputTokens());
    }
}

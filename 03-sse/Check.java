import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Checker for Exercise 03.  Start:  java SseServer.java     Then:  java Check.java
 */
public class Check {
    record Event(String id, String event, String data, long receivedAt) {}
    record Stream(int status, String contentType, List<Event> events, List<String> comments) {}

    public static void main(String[] args) throws Exception {
        Kit.requireServer("http://localhost:8083", "java SseServer.java");

        Kit.section("TODO(1) + TODO(2) streaming");
        String fast = Kit.field(Kit.call("POST", "/orders", "{\"stepMs\":300}").body(), "orderId");
        Kit.test("GET /orders/{id}/events -> 200 text/event-stream", () -> {
            Stream s = open(fast, null);
            Kit.expect(s.status() == 200, "status was " + s.status());
            Kit.expect(s.contentType() != null && s.contentType().startsWith("text/event-stream"),
                    "Content-Type must be text/event-stream, was " + s.contentType());
        });
        Kit.test("receives all 5 statuses, ids 1..5, event name 'status', then the stream ends", () -> {
            Stream s = open(fast, null);
            List<String> ids = s.events().stream().map(Event::id).toList();
            Kit.expect(ids.equals(List.of("1", "2", "3", "4", "5")), "event ids were " + ids);
            Kit.expect(s.events().stream().allMatch(e -> "status".equals(e.event())), "every event needs 'event: status'");
            Kit.expect(s.events().get(4).data().contains("DELIVERED"), "last event should be DELIVERED");
            Kit.expect(s.events().get(0).data().startsWith("{"), "data should be the JSON snapshot");
        });

        Kit.test("events arrive LIVE, one by one (you're flushing), not in one lump at the end", () -> {
            String slow = Kit.field(Kit.call("POST", "/orders", "{\"stepMs\":700}").body(), "orderId");
            long start = System.currentTimeMillis();
            Stream s = open(slow, null);
            long first = s.events().get(0).receivedAt() - start;
            long last = s.events().get(s.events().size() - 1).receivedAt() - start;
            Kit.expect(first < 1000, "first event took " + first + " ms -- did you flush()?");
            Kit.expect(last - first > 1000, "all events arrived together -- they should be spread over time");
        });

        Kit.section("TODO(2) resuming with Last-Event-ID");
        Kit.test("header Last-Event-ID: 3 -> only events 4 and 5", () -> {
            List<String> ids = open(fast, "3").events().stream().map(Event::id).toList();
            Kit.expect(ids.equals(List.of("4", "5")), "after Last-Event-ID 3 got ids " + ids);
        });
        Kit.test("?lastEventId=2 also works (for a brand-new EventSource)", () -> {
            HttpResponse<java.util.stream.Stream<String>> r = Kit.CLIENT.send(
                    HttpRequest.newBuilder(URI.create(Kit.base + "/orders/" + fast + "/events?lastEventId=2")).build(),
                    HttpResponse.BodyHandlers.ofLines());
            long count = r.body().filter(l -> l.startsWith("id:")).count();
            Kit.expect(count == 3, "expected 3 events (3,4,5), got " + count);
        });
        Kit.test("finished order + Last-Event-ID: 5 -> 204 (tells the browser to stop reconnecting)", () -> {
            Kit.expect(open(fast, "5").status() == 204, "expected 204");
        });
        Kit.test("unknown order -> 404", () -> Kit.expect(open("ord_nope", null).status() == 404, "expected 404"));

        Kit.summary("Exercise 03");
        System.out.println("Now open http://localhost:8083, place an order, and press 'Simulate network drop'.");
    }

    /** Opens the SSE stream and reads it until the server closes it (max 20 s). */
    static Stream open(String orderId, String lastEventId) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(Kit.base + "/orders/" + orderId + "/events"));
        if (lastEventId != null) b.header("Last-Event-ID", lastEventId);
        HttpResponse<java.util.stream.Stream<String>> r = Kit.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofLines());
        List<Event> events = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        CompletableFuture<Void> reading = CompletableFuture.runAsync(() -> {
            Map<String, String> cur = new HashMap<>();
            r.body().forEach(line -> {
                if (line.isEmpty()) {
                    if (cur.containsKey("data")) events.add(new Event(cur.get("id"), cur.get("event"), cur.get("data"), System.currentTimeMillis()));
                    cur.clear();
                } else if (line.startsWith(":")) {
                    comments.add(line);
                } else {
                    int c = line.indexOf(':');
                    String key = c < 0 ? line : line.substring(0, c);
                    String val = c < 0 ? "" : line.substring(c + 1).stripLeading();
                    cur.put(key, val);
                }
            });
        });
        try {
            reading.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("stream never ended -- close it after DELIVERED. Got " + events.size() + " events");
        }
        return new Stream(r.statusCode(), r.headers().firstValue("Content-Type").orElse(null), events, comments);
    }
}

// =====================================================================
// Tiny test kit (given) — no JUnit needed.
// =====================================================================
final class Kit {
    static String base;
    static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newHttpClient();
    static int passed, failed;

    interface Body { void run() throws Exception; }

    static void requireServer(String base, String startCommand) {
        Kit.base = base;
        try {
            CLIENT.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.out.println("Cannot reach " + base + " -- start the server first:  " + startCommand);
            System.exit(1);
        }
    }

    static void section(String title) { System.out.println("\n-- " + title + " --"); }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + "\n         " + t.getMessage());
        }
    }

    static void summary(String exercise) {
        System.out.printf("%n%d passed, %d failed%s%n", passed, failed, failed == 0 ? "  -> " + exercise + " complete!" : "");
    }

    static java.net.http.HttpResponse<String> call(String method, String path, String body, String... headers) throws Exception {
        var b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(path.startsWith("http") ? path : base + path))
                .timeout(java.time.Duration.ofSeconds(40))
                .method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                             : java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return CLIENT.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    static void expect(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    static void expectStatus(java.net.http.HttpResponse<String> r, int status) {
        if (r.statusCode() != status)
            throw new AssertionError("expected HTTP " + status + " but got " + r.statusCode() + "  body=" + r.body());
    }

    /** Pulls "name": value out of JSON text (first match). Good enough for tests. */
    static String field(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|[-\\d.]+|true|false|null)").matcher(json);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static int intField(String json, String name) {
        String v = field(json, name);
        if (v == null) throw new AssertionError("field \"" + name + "\" missing in " + json);
        return (int) Double.parseDouble(v);
    }
}

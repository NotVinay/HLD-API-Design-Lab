import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.lang.System.currentTimeMillis;

/**
 * Checker for the long-polling endpoint (TODO 1).  Start:  java TrackingServer.java
 * Then:  java Check.java
 * For TODO 2 and 3, run PollingClient and read its scorecard (see README).
 */
public class Check {
    public static void main(String[] args) throws Exception {
        Kit.requireServer("http://localhost:8082", "java TrackingServer.java");

        Kit.section("given: short polling");
        String[] id = new String[1];
        Kit.test("POST /orders -> 201, GET status -> 200 PLACED v1", () -> {
            var r = Kit.call("POST", "/orders", "{\"stepMs\":800}");
            Kit.expectStatus(r, 201);
            id[0] = Kit.field(r.body(), "orderId");
            var s = Kit.call("GET", "/orders/" + id[0] + "/status", null);
            Kit.expectStatus(s, 200);
            Kit.expect(Kit.intField(s.body(), "version") >= 1, "version missing");
        });

        Kit.section("TODO(1) long polling");
        Kit.test("sinceVersion=0 answers immediately (there is already news)", () -> {
            long t = currentTimeMillis();
            var r = Kit.call("GET", "/orders/" + id[0] + "/status?sinceVersion=0&timeoutMs=5000", null);
            Kit.expectStatus(r, 200);
            Kit.expect(currentTimeMillis() - t < 300, "took " + (currentTimeMillis() - t) + " ms -- should not wait when news exists");
        });
        Kit.test("sinceVersion=current WAITS for the next change, then returns it", () -> {
            var now = Kit.call("GET", "/orders/" + id[0] + "/status", null);
            int v = Kit.intField(now.body(), "version");
            long t = currentTimeMillis();
            var r = Kit.call("GET", "/orders/" + id[0] + "/status?sinceVersion=" + v + "&timeoutMs=10000", null);
            long took = currentTimeMillis() - t;
            Kit.expectStatus(r, 200);
            Kit.expect(Kit.intField(r.body(), "version") > v, "returned an old version");
            Kit.expect(took > 100, "returned after " + took + " ms -- it should have waited for the kitchen");
        });
        Kit.test("no change before timeoutMs -> 204 No Content after ~timeout", () -> {
            String slow = Kit.field(Kit.call("POST", "/orders", "{\"stepMs\":60000}").body(), "orderId");
            long t = currentTimeMillis();
            var r = Kit.call("GET", "/orders/" + slow + "/status?sinceVersion=1&timeoutMs=1000", null);
            long took = currentTimeMillis() - t;
            Kit.expectStatus(r, 204);
            Kit.expect(took >= 900 && took < 3000, "expected ~1000 ms wait, took " + took + " ms");
        });
        Kit.test("3 clients waiting on the same order ALL wake up (signalAll, not signal)", () -> {
            String o = Kit.field(Kit.call("POST", "/orders", "{\"stepMs\":800}").body(), "orderId");
            List<CompletableFuture<Integer>> waiters = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                waiters.add(CompletableFuture.supplyAsync(() -> {
                    try { return Kit.call("GET", "/orders/" + o + "/status?sinceVersion=1&timeoutMs=4000", null).statusCode(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }));
            }
            for (var w : waiters) Kit.expect(w.get() == 200, "a waiting client got " + w.get() + " instead of 200");
        });
        Kit.test("sinceVersion=abc -> 400", () ->
                Kit.expectStatus(Kit.call("GET", "/orders/" + id[0] + "/status?sinceVersion=abc", null), 400));
        Kit.test("unknown order -> 404", () ->
                Kit.expectStatus(Kit.call("GET", "/orders/ord_nope/status?sinceVersion=0", null), 404));

        Kit.summary("Exercise 02 (server)");
        System.out.println("Now finish TODO 2 & 3 and compare:  java PollingClient.java short 2000   vs   java PollingClient.java long");
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Checker for Exercise 04.  Start:  java ChatServer.java    Then:  java Check.java
 */
public class Check {

    /** A test user: collects every message the server pushes to it. */
    static final class Peer implements WebSocket.Listener {
        final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket ws;
        final StringBuilder partial = new StringBuilder();

        static Peer join(String order, String user) {
            Peer p = new Peer();
            p.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:8084/chat?order=" + order + "&user=" + user), p)
                    .orTimeout(5, TimeUnit.SECONDS).join();
            return p;
        }

        @Override public void onOpen(WebSocket ws) { ws.request(1); }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { inbox.add(partial.toString()); partial.setLength(0); }
            ws.request(1);
            return null;
        }

        void say(String json) { ws.sendText(json, true).join(); }

        /** Waits for the next message containing `needle` (skipping others). */
        String await(String needle) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                String m = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && m.contains(needle)) return m;
            }
            throw new AssertionError("never received a message containing " + needle);
        }

        void expectSilence(String needle) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 700;
            while (System.currentTimeMillis() < deadline) {
                String m = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && m.contains(needle)) throw new AssertionError("should NOT have received: " + m);
            }
        }

        void leave() { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join(); }
    }

    public static void main(String[] args) throws Exception {
        Kit.requireServer("http://localhost:8084", "java ChatServer.java");
        String room = "ord_" + System.nanoTime() % 100000, other = room + "x";

        Kit.section("plain HTTP still works on the same port");
        Kit.test("GET /chat without Upgrade -> 400", () -> Kit.expectStatus(Kit.call("GET", "/chat?order=a&user=b", null), 400));

        Kit.section("TODO(1) handshake");
        Peer[] p = new Peer[3];
        Kit.test("JDK WebSocket client connects (it verifies your Sec-WebSocket-Accept)", () -> {
            try { p[0] = Peer.join(room, "alice"); }
            catch (Exception e) { throw new AssertionError("handshake rejected -- check acceptKey(). " + e); }
        });
        if (p[0] == null) { Kit.summary("Exercise 04"); System.exit(1); }

        Kit.section("TODO(2) join");
        Kit.test("alice is told she joined", () -> p[0].await("alice joined"));
        Kit.test("bob joins the same order -> alice is told, online list has both", () -> {
            p[1] = Peer.join(room, "bob");
            String m = p[0].await("bob joined");
            Kit.expect(m.contains("\"alice\"") && m.contains("\"bob\""), "online list should contain alice and bob: " + m);
        });
        p[2] = Peer.join(other, "carol");

        Kit.section("TODO(3) messages");
        Kit.test("alice says hi -> bob receives it with from=alice", () -> {
            p[0].say("{\"type\":\"message\",\"text\":\"hi from the gate\"}");
            String m = p[1].await("hi from the gate");
            Kit.expect(m.contains("\"from\":\"alice\""), "message should say who sent it: " + m);
            Kit.expect(m.contains("\"at\""), "message should carry a timestamp 'at': " + m);
        });
        Kit.test("alice also gets her own message back (delivery receipt)", () -> p[0].await("hi from the gate"));
        Kit.test("carol, in a different order's room, gets nothing", () -> p[2].expectSilence("hi from the gate"));
        Kit.test("typing goes to bob but NOT back to alice", () -> {
            p[0].say("{\"type\":\"typing\"}");
            p[1].await("\"typing\"");
            p[0].expectSilence("\"typing\"");
        });
        Kit.test("blank messages are ignored", () -> {
            p[0].say("{\"type\":\"message\",\"text\":\"   \"}");
            p[1].expectSilence("\"message\"");
        });
        Kit.test("unknown type -> error back to the sender", () -> {
            p[0].say("{\"type\":\"dance\"}");
            p[0].await("\"error\"");
        });

        Kit.section("TODO(4) leave");
        Kit.test("bob leaves -> alice is told", () -> {
            p[1].leave();
            String m = p[0].await("bob left");
            Kit.expect(!m.contains("\"bob\""), "bob should no longer be in the online list: " + m);
        });

        Kit.summary("Exercise 04");
        System.out.println("Now open http://localhost:8084 in two tabs and chat. Then try: java ChatClient.java ord_42 You");
        System.exit(0);
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

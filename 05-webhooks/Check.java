import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checker for Exercise 05. Start BOTH (normal mode, no flags):
 *     java PayGateway.java      and      java MerchantApp.java
 * Then:  java Check.java
 */
public class Check {
    static final String GATEWAY = "http://localhost:8091", MERCHANT = "http://localhost:8092";
    static final String SECRET = "whsec_quickbite_demo_123";

    public static void main(String[] args) throws Exception {
        Kit.requireServer(GATEWAY, "java PayGateway.java");
        Kit.requireServer(MERCHANT, "java MerchantApp.java");

        Kit.section("Gateway: TODO(1) sign + TODO(2) retries");
        Kit.test("gateway retries a failing endpoint with growing gaps (1s, 2s...) until it gets a 2xx", () -> {
            List<Long> hits = new ArrayList<>();
            List<String> sigs = new ArrayList<>();
            HttpServer flaky = HttpServer.create(new InetSocketAddress(8093), 0);
            flaky.createContext("/hook", ex -> {
                hits.add(System.currentTimeMillis());
                sigs.add(ex.getRequestHeaders().getFirst("QuickPay-Signature"));
                ex.getRequestBody().readAllBytes();
                ex.sendResponseHeaders(hits.size() < 3 ? 500 : 200, -1);
                ex.close();
            });
            flaky.start();
            try {
                Kit.call("POST", GATEWAY + "/payments", "{\"orderId\":\"ord_test\",\"amount\":100,\"webhookUrl\":\"http://localhost:8093/hook\"}");
                long deadline = System.currentTimeMillis() + 9000;
                while (hits.size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(100);
                Thread.sleep(1500);   // make sure no 4th attempt arrives after the 200
                Kit.expect(hits.size() == 3, "expected exactly 3 attempts (fail, fail, OK), got " + hits.size());
                long gap1 = hits.get(1) - hits.get(0), gap2 = hits.get(2) - hits.get(1);
                Kit.expect(gap1 >= 800, "first retry came after " + gap1 + " ms, expected ~1000");
                Kit.expect(gap2 >= gap1 * 1.5, "backoff should grow: gaps were " + gap1 + " ms then " + gap2 + " ms");
                Kit.expect(sigs.get(0) != null && sigs.get(0).matches("t=\\d+,v1=[0-9a-f]{64}"),
                        "QuickPay-Signature should look like t=<seconds>,v1=<64 hex chars>, was " + sigs.get(0));
            } finally {
                flaky.stop(0);
            }
        });

        Kit.section("Merchant: end to end");
        String[] paid = new String[1];
        Kit.test("POST /orders {amount:499} -> PENDING_PAYMENT, then PAID once the webhook lands", () -> {
            var r = Kit.call("POST", MERCHANT + "/orders", "{\"amount\":499}");
            Kit.expectStatus(r, 201);
            paid[0] = Kit.field(r.body(), "orderId");
            Kit.expect("PENDING_PAYMENT".equals(Kit.field(r.body(), "status")), "new order should be PENDING_PAYMENT");
            Kit.expect(waitForStatus(paid[0], "PAID"), "order never became PAID (is the webhook verified + applied?)");
        });
        Kit.test("amount 20000 -> PAYMENT_FAILED", () -> {
            String id = Kit.field(Kit.call("POST", MERCHANT + "/orders", "{\"amount\":20000}").body(), "orderId");
            Kit.expect(waitForStatus(id, "PAYMENT_FAILED"), "order never became PAYMENT_FAILED");
        });

        Kit.section("Merchant: TODO(3) signature verification");
        String event = "{\"id\":\"evt_forged1\",\"type\":\"payment.succeeded\",\"data\":{\"orderId\":\"%s\"}}";
        Kit.test("forged signature -> 401", () -> {
            String body = event.formatted("ord_x");
            long t = now();
            Kit.expectStatus(webhook(body, "t=" + t + ",v1=" + "0".repeat(64)), 401);
        });
        Kit.test("missing signature header -> 401", () ->
                Kit.expectStatus(Kit.call("POST", MERCHANT + "/webhooks/quickpay", event.formatted("ord_x")), 401));
        Kit.test("signed with the wrong secret -> 401", () -> {
            String body = event.formatted("ord_x");
            long t = now();
            Kit.expectStatus(webhook(body, "t=" + t + ",v1=" + hmac("not-the-secret", t + "." + body)), 401);
        });
        Kit.test("valid signature but 10 minutes old (replay attack) -> 401", () -> {
            String body = event.formatted("ord_x");
            long t = now() - 600;
            Kit.expectStatus(webhook(body, "t=" + t + ",v1=" + hmac(SECRET, t + "." + body)), 401);
        });
        Kit.test("body tampered after signing (PAYMENT_FAILED -> succeeded) -> 401", () -> {
            String original = event.formatted("ord_x").replace("succeeded", "failed");
            long t = now();
            String sig = "t=" + t + ",v1=" + hmac(SECRET, t + "." + original);
            Kit.expectStatus(webhook(original.replace("failed", "succeeded"), sig), 401);
        });

        Kit.section("Merchant: TODO(4) idempotency + TODO(5) apply");
        Kit.test("a correctly signed event is accepted (200) and applied", () -> {
            String id = Kit.field(Kit.call("POST", MERCHANT + "/orders", "{\"amount\":20000}").body(), "orderId");
            waitForStatus(id, "PAYMENT_FAILED");
            String body = "{\"id\":\"evt_manual_" + System.nanoTime() + "\",\"type\":\"payment.succeeded\",\"data\":{\"orderId\":\"" + id + "\"}}";
            Kit.expectStatus(signedWebhook(body), 200);
            Kit.expect(waitForStatus(id, "PAID"), "event was accepted but the order was not updated");
        });
        Kit.test("the same event delivered twice -> both 200, processed once, duplicate counted", () -> {
            int before = Kit.intField(Kit.call("GET", MERCHANT + "/stats", null).body(), "duplicatesIgnored");
            String body = "{\"id\":\"evt_dup_" + System.nanoTime() + "\",\"type\":\"payment.succeeded\",\"data\":{\"orderId\":\"" + paid[0] + "\"}}";
            Kit.expectStatus(signedWebhook(body), 200);
            Kit.expectStatus(signedWebhook(body), 200);
            int after = Kit.intField(Kit.call("GET", MERCHANT + "/stats", null).body(), "duplicatesIgnored");
            Kit.expect(after == before + 1, "duplicatesIgnored went from " + before + " to " + after + " (expected +1)");
        });
        Kit.test("event for an unknown order -> 200 (retrying won't fix it)", () -> {
            String body = "{\"id\":\"evt_ghost_" + System.nanoTime() + "\",\"type\":\"payment.succeeded\",\"data\":{\"orderId\":\"ord_ghost\"}}";
            Kit.expectStatus(signedWebhook(body), 200);
        });

        Kit.summary("Exercise 05");
        System.out.println("Now restart with  java PayGateway.java --duplicate  and  java MerchantApp.java --flaky  and watch both logs.");
    }

    static long now() { return System.currentTimeMillis() / 1000; }

    static java.net.http.HttpResponse<String> webhook(String body, String sig) throws Exception {
        return Kit.call("POST", MERCHANT + "/webhooks/quickpay", body, "QuickPay-Signature", sig);
    }

    static java.net.http.HttpResponse<String> signedWebhook(String body) throws Exception {
        long t = now();
        return webhook(body, "t=" + t + ",v1=" + hmac(SECRET, t + "." + body));
    }

    static boolean waitForStatus(String orderId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (status.equals(Kit.field(Kit.call("GET", MERCHANT + "/orders/" + orderId, null).body(), "status"))) return true;
            Thread.sleep(200);
        }
        return false;
    }

    static String hmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
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

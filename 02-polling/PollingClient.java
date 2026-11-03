import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exercise 02 — the "customer app" that tracks an order.
 *
 *   java PollingClient.java short 2000    # short polling, ask every 2000 ms
 *   java PollingClient.java long          # long polling
 *
 * It places an order, follows it until DELIVERED, then prints a scorecard:
 * how many requests were made, how many were wasted, how late updates were noticed,
 * and how many status changes were MISSED entirely.
 */
public class PollingClient {
    static final String BASE = "http://localhost:8082";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // Scorecard
    static int requests = 0;          // total HTTP calls
    static int wasted = 0;            // calls that brought no news
    static int missed = 0;            // status changes we never saw
    static final List<Long> lags = new ArrayList<>();   // ms between "it happened" and "we noticed"

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "short";
        long intervalMs = args.length > 1 ? Long.parseLong(args[1]) : 2000;

        String created = send("POST", "/orders", "{}").body();
        String id = field(created, "orderId");
        System.out.println("Placed order " + id + " -- tracking with " + mode.toUpperCase() + " polling\n");
        requests = 0; // don't count the POST

        long start = System.currentTimeMillis();
        if (mode.equals("long")) longPoll(id);
        else shortPoll(id, intervalMs);

        long avgLag = lags.isEmpty() ? 0 : lags.stream().mapToLong(Long::longValue).sum() / lags.size();
        System.out.println("\n================ SCORECARD (" + mode + ") ================");
        System.out.printf("Total time          : %.1f s%n", (System.currentTimeMillis() - start) / 1000.0);
        System.out.printf("HTTP requests       : %d%n", requests);
        System.out.printf("Wasted requests     : %d  (no news)%n", wasted);
        System.out.printf("Avg notice delay    : %d ms  (how stale the UI was)%n", avgLag);
        System.out.printf("Missed status steps : %d%n", missed);
    }

    /**
     * TODO(2): Short polling.
     *   Loop: GET /orders/{id}/status, then Thread.sleep(intervalMs).
     *   - requests++ for every call
     *   - if the version is the same as last time -> wasted++
     *   - otherwise call onUpdate(body, lastVersion) and remember the new version
     *   - stop when status is DELIVERED
     */
    static void shortPoll(String id, long intervalMs) throws Exception {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /**
     * TODO(3): Long polling.
     *   Loop: GET /orders/{id}/status?sinceVersion={lastVersion}&timeoutMs=10000  — no sleep needed!
     *   - requests++ for every call
     *   - 204 No Content -> wasted++ and just ask again
     *   - 200 -> onUpdate(body, lastVersion), remember the version, stop at DELIVERED
     */
    static void longPoll(String id) throws Exception {
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    // ---------------- given helpers ----------------

    /** Prints the update and records lag + missed steps. */
    static void onUpdate(String body, int previousVersion) {
        int version = Integer.parseInt(field(body, "version"));
        long lag = System.currentTimeMillis() - Long.parseLong(field(body, "changedAt"));
        if (previousVersion > 0 && version > previousVersion + 1) {
            missed += version - previousVersion - 1;
            System.out.printf("   !! skipped %d status change(s) between polls%n", version - previousVersion - 1);
        }
        lags.add(lag);
        System.out.printf("%s  %-17s v%d   (noticed %4d ms late)%n", LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), field(body, "status"), version, lag);
    }

    static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(40))     // must be longer than the server's long-poll timeout
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw new IllegalStateException("HTTP " + r.statusCode() + ": " + r.body());
        return r;
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|[-\\d.]+)").matcher(json);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }
}

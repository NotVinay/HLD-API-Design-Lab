import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exercise 02 — Short polling vs Long polling.
 *
 * Run:  java TrackingServer.java            (http://localhost:8082)
 *
 *   POST /orders                         body {} or {"stepMs":3000}  -> 201 {orderId,...}
 *   GET  /orders/{id}/status             -> 200 right away              (short polling — given)
 *   GET  /orders/{id}/status?sinceVersion=N&timeoutMs=T
 *                                        -> waits until version > N    (long polling — TODO 1)
 *                                           200 with the new status, or 204 No Content on timeout
 *
 * Once created, every order moves forward on its own (the "kitchen" simulation):
 *   PLACED -> ACCEPTED -> PREPARING -> OUT_FOR_DELIVERY -> DELIVERED
 */
public class TrackingServer {

    static final int PORT = 8082;
    static final List<String> LIFECYCLE = List.of("PLACED", "ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED");
    static final long MAX_WAIT_MS = 30_000;

    /** One order being tracked. All reads/writes of status & version happen while holding `lock`. */
    static final class TrackedOrder {
        final String id;
        final ReentrantLock lock = new ReentrantLock();
        final Condition changed = lock.newCondition();   // signalled every time the status changes
        int step = 0;                                    // index into LIFECYCLE
        int version = 1;                                 // bumps on every change
        long changedAt = System.currentTimeMillis();

        TrackedOrder(String id) { this.id = id; }

        boolean isDone() { return step == LIFECYCLE.size() - 1; }

        /** Called by the kitchen simulation. */
        void advance() {
            lock.lock();
            try {
                step++;
                version++;
                changedAt = System.currentTimeMillis();
                changed.signalAll();     // wake up EVERY long-poll request waiting on this order
            } finally {
                lock.unlock();
            }
            System.out.printf("   [kitchen] %s -> %s (v%d)%n", id, LIFECYCLE.get(step), version);
        }

        /** Copy of the current state. Call it while holding the lock. */
        Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", id);
            m.put("status", LIFECYCLE.get(step));
            m.put("version", version);
            m.put("changedAt", changedAt);
            return m;
        }
    }

    static final Map<String, TrackedOrder> ORDERS = new ConcurrentHashMap<>();
    static final AtomicInteger SEQ = new AtomicInteger(100);
    static final ScheduledExecutorService KITCHEN = Executors.newScheduledThreadPool(2);
    static final AtomicInteger REQUESTS = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        System.setProperty("sun.net.httpserver.nodelay", "true"); // send small responses immediately
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/orders", Http.safe(TrackingServer::route));
        // Long polling parks a request for up to 30s. With a fixed pool of 8 threads, 8 waiting
        // clients would freeze the server. Virtual threads (Java 21) make a parked request cheap.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Tracking server on http://localhost:" + PORT);
    }

    static void route(HttpExchange ex) throws IOException {
        REQUESTS.incrementAndGet();
        String[] parts = ex.getRequestURI().getPath().split("/");
        String method = ex.getRequestMethod();
        if (parts.length == 2 && method.equals("POST")) { createOrder(ex); return; }
        if (parts.length == 4 && parts[3].equals("status") && method.equals("GET")) {
            TrackedOrder order = ORDERS.get(parts[2]);
            if (order == null) { Http.send(ex, 404, Map.of("error", "Unknown order " + parts[2])); return; }
            Map<String, String> q = Http.query(ex);
            if (q.containsKey("sinceVersion")) longPoll(ex, order, q);
            else shortPoll(ex, order);
            return;
        }
        Http.send(ex, 404, Map.of("error", "Not found"));
    }

    static void createOrder(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        long stepMs = 3000;
        if (!raw.isBlank() && Json.parse(raw) instanceof Map<?, ?> m && m.get("stepMs") instanceof Number n) {
            stepMs = n.longValue();
        }
        TrackedOrder order = new TrackedOrder("ord_" + SEQ.incrementAndGet());
        ORDERS.put(order.id, order);
        scheduleNextStep(order, stepMs);
        order.lock.lock();
        try { Http.send(ex, 201, order.snapshot()); } finally { order.lock.unlock(); }
    }

    /** The kitchen: each step takes stepMs, +/- 50% so it feels real (and so polling can miss steps!). */
    static void scheduleNextStep(TrackedOrder order, long stepMs) {
        long delay = (long) (stepMs * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
        KITCHEN.schedule(() -> {
            order.advance();
            if (!order.isDone()) scheduleNextStep(order, stepMs);
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** SHORT polling (given): answer immediately with whatever the status is right now. */
    static void shortPoll(HttpExchange ex, TrackedOrder order) throws IOException {
        Map<String, Object> snap;
        order.lock.lock();
        try { snap = order.snapshot(); } finally { order.lock.unlock(); }
        Http.send(ex, 200, snap);
    }

    /**
     * LONG polling.
     * TODO(1): Hold the request open until the order's version is greater than `sinceVersion`,
     *          or until timeoutMs passes (default 25000, cap at MAX_WAIT_MS).
     *   - version already > sinceVersion  -> 200 immediately with the snapshot
     *   - change happens while waiting    -> 200 with the NEW snapshot
     *   - timeout                         -> 204 No Content (client simply asks again)
     *   - bad numbers                     -> 400
     *
     * Hints:
     *   - order.lock.lock() ... finally order.lock.unlock()
     *   - order.changed.awaitNanos(remainingNanos) sleeps until advance() signals OR time runs out.
     *     It returns early on a signal, so re-check the condition in a `while` loop.
     *   - Take the snapshot inside the lock, send the HTTP response AFTER unlocking.
     */
    static void longPoll(HttpExchange ex, TrackedOrder order, Map<String, String> q) throws IOException {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }
}

// =====================================================================
// Helpers below — you don't need to change anything here.
// =====================================================================

final class Http {
    static final class BadRequest extends RuntimeException {
        BadRequest(String m) { super(m); }
    }

    /** Wraps a handler: TODOs become 501, bad JSON becomes 400, bugs become 500. */
    static HttpHandler safe(HttpHandler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (UnsupportedOperationException e) {
                trySend(ex, 501, Map.of("error", e.getMessage()));
            } catch (BadRequest e) {
                trySend(ex, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                trySend(ex, 500, Map.of("error", "Server bug: " + e));
            } finally {
                ex.close();
            }
        };
    }

    private static void trySend(HttpExchange ex, int status, Object body) {
        try { send(ex, status, body); } catch (Exception ignored) { /* response already started */ }
    }

    static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) ex.getResponseHeaders().set("Content-Type", "application/json");
        System.out.printf("%-6s %-40s -> %d%n", ex.getRequestMethod(), ex.getRequestURI(), status);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static void methodNotAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        send(ex, 405, Map.of("error", ex.getRequestMethod() + " not allowed here. Allowed: " + allow));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(HttpExchange ex) throws IOException {
        return parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String raw) {
        if (raw.isBlank()) throw new BadRequest("Request body is required");
        Object parsed;
        try { parsed = Json.parse(raw); } catch (RuntimeException e) { throw new BadRequest("Malformed JSON: " + e.getMessage()); }
        if (!(parsed instanceof Map)) throw new BadRequest("Body must be a JSON object");
        return (Map<String, Object>) parsed;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                  kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }
}

/** A tiny JSON reader/writer so the exercise needs nothing but the JDK. */
final class Json {
    static Object parse(String s) {
        Parser p = new Parser(s);
        try {
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw new IllegalArgumentException("Unexpected text at position " + p.i);
            return v;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
    }

    static String write(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return quote(s);
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Enum<?> e) return quote(e.name());
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k, v) -> j.add(quote(String.valueOf(k)) + ":" + write(v)));
            return j.toString();
        }
        if (o instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object v : c) j.add(write(v));
            return j.toString();
        }
        if (o instanceof Record r) {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                for (var rc : r.getClass().getRecordComponents()) {
                    var accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    m.put(rc.getName(), accessor.invoke(r));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return write(m);
        }
        return quote(o.toString());
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + i);
            String n = s.substring(st, i);
            return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : Double.parseDouble(n);
        }

        String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        void expect(char c) {
            ws();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + i);
            i++;
        }
    }
}

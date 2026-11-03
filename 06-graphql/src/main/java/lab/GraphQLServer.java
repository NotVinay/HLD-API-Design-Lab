package lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 06 — GraphQL.
 *
 * Run:  mvn -q compile exec:java        then open http://localhost:8086
 *
 *   POST /graphql   {"query":"...","variables":{...}}   <- the ONE GraphQL endpoint
 *   GET  /          a tiny playground page
 *   GET  /rest/restaurants, GET /rest/restaurants/{id}/menu   <- the REST version, to compare
 *
 * How it works: the schema (src/main/resources/schema.graphqls) lists every type and field.
 * For each field that needs code, you register a DataFetcher ("resolver").
 * Fields without one (name, price, ...) are read straight off the Java record.
 */
public class GraphQLServer {

    static final int PORT = 8086;

    // ---------------- Data (pretend these are DB tables) ----------------
    record Restaurant(String id, String name, String cuisine, double rating, int deliveryMinutes) {}
    record MenuItem(String id, String restaurantId, String name, int price, boolean veg, boolean bestseller) {}
    record Review(String id, String restaurantId, String author, int rating, String comment) {}

    static final List<Restaurant> RESTAURANTS = List.of(
            new Restaurant("r1", "Dosa Junction", "South Indian", 4.5, 25),
            new Restaurant("r2", "Biryani Brothers", "Hyderabadi", 4.3, 35),
            new Restaurant("r3", "Punjab Da Dhaba", "North Indian", 4.1, 30),
            new Restaurant("r4", "Idli Express", "South Indian", 4.6, 20),
            new Restaurant("r5", "Wok This Way", "Chinese", 3.9, 40));

    static final List<MenuItem> MENU = List.of(
            new MenuItem("m1", "r1", "Masala Dosa", 129, true, true),
            new MenuItem("m2", "r1", "Rava Dosa", 149, true, false),
            new MenuItem("m3", "r1", "Filter Coffee", 49, true, true),
            new MenuItem("m4", "r2", "Chicken Dum Biryani", 299, false, true),
            new MenuItem("m5", "r2", "Veg Biryani", 219, true, false),
            new MenuItem("m6", "r2", "Double Ka Meetha", 99, true, false),
            new MenuItem("m7", "r3", "Butter Chicken", 329, false, true),
            new MenuItem("m8", "r3", "Dal Makhani", 229, true, true),
            new MenuItem("m9", "r3", "Garlic Naan", 59, true, false),
            new MenuItem("m10", "r4", "Ghee Podi Idli", 119, true, true),
            new MenuItem("m11", "r4", "Medu Vada", 79, true, false),
            new MenuItem("m12", "r5", "Hakka Noodles", 189, true, true),
            new MenuItem("m13", "r5", "Chilli Chicken", 249, false, false));

    static final List<Review> REVIEWS = new CopyOnWriteArrayList<>(List.of(
            new Review("v1", "r1", "Asha", 5, "Crispiest dosa in Koramangala"),
            new Review("v2", "r1", "Rahul", 4, "Coffee could be stronger"),
            new Review("v3", "r2", "Imran", 5, "Proper dum biryani"),
            new Review("v4", "r3", "Simran", 4, null),
            new Review("v5", "r4", "Karthik", 5, "Podi idli is unreal")));
    static final AtomicInteger REVIEW_SEQ = new AtomicInteger(5);

    /** Counts how many times the menu "table" is queried per request — watch for N+1. */
    static final AtomicInteger MENU_QUERIES = new AtomicInteger();

    // =====================================================================
    // RESOLVERS — your part
    // =====================================================================

    /**
     * TODO(1): Query.restaurants(cuisine: String)
     *   Return all RESTAURANTS, or only those whose cuisine equalsIgnoreCase the argument.
     *   Read an argument with:  String cuisine = env.getArgument("cuisine");   (null if not passed)
     */
    static List<Restaurant> restaurants(DataFetchingEnvironment env) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Query.restaurant(id: ID!)  -> the restaurant, or null if not found.
     *   (null is fine: the schema says `Restaurant` without "!", so it's nullable.)
     */
    static Restaurant restaurant(DataFetchingEnvironment env) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /**
     * TODO(3): Restaurant.menu(vegOnly: Boolean = false)
     *   This is a FIELD resolver: it runs once per restaurant, only if the query asks for `menu`.
     *   - The parent restaurant:   Restaurant r = env.getSource();
     *   - The argument:            boolean vegOnly = env.getArgument("vegOnly");
     *   - Call MENU_QUERIES.incrementAndGet() (this is our pretend "SELECT * FROM menu WHERE ...")
     *   - Return MENU items for r.id(), filtered by veg when vegOnly is true.
     */
    static List<MenuItem> menu(DataFetchingEnvironment env) {
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    /** Restaurant.reviews(last: Int = 3) — GIVEN, use it as the example for TODO 3. */
    static List<Review> reviews(DataFetchingEnvironment env) {
        Restaurant r = env.getSource();
        int last = env.getArgument("last");
        List<Review> mine = REVIEWS.stream().filter(v -> v.restaurantId().equals(r.id())).toList();
        List<Review> newestFirst = new ArrayList<>(mine);
        Collections.reverse(newestFirst);
        return newestFirst.subList(0, Math.min(last, newestFirst.size()));
    }

    /**
     * TODO(4): Mutation.addReview(input: ReviewInput!)
     *   Map<String, Object> input = env.getArgument("input");
     *   - restaurantId must exist          -> else throw new IllegalArgumentException("No restaurant r99")
     *   - rating must be 1..5              -> else throw new IllegalArgumentException("rating must be 1..5")
     *   - create Review("v" + REVIEW_SEQ.incrementAndGet(), restaurantId, "guest", rating, comment)
     *   - add it to REVIEWS and return it
     *   Note: a thrown exception does NOT give an HTTP 500. It shows up in the "errors" array (see README Q2).
     */
    static Review addReview(DataFetchingEnvironment env) {
        throw new UnsupportedOperationException("TODO(4) not implemented yet");
    }

    // =====================================================================
    // WIRING + HTTP (given)
    // =====================================================================

    static GraphQL buildGraphQL() throws IOException {
        String sdl;
        try (var in = GraphQLServer.class.getResourceAsStream("/schema.graphqls")) {
            sdl = new String(Objects.requireNonNull(in, "schema.graphqls not found").readAllBytes(), StandardCharsets.UTF_8);
        }
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t
                        .dataFetcher("restaurants", GraphQLServer::restaurants)
                        .dataFetcher("restaurant", GraphQLServer::restaurant))
                .type("Restaurant", t -> t
                        .dataFetcher("menu", GraphQLServer::menu)
                        .dataFetcher("reviews", GraphQLServer::reviews))
                .type("Mutation", t -> t
                        .dataFetcher("addReview", GraphQLServer::addReview))
                .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(sdl), wiring);
        return GraphQL.newGraphQL(schema).build();
    }

    static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        GraphQL graphQL = buildGraphQL();
        System.setProperty("sun.net.httpserver.nodelay", "true");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/graphql", ex -> handleGraphQL(ex, graphQL));
        server.createContext("/rest/restaurants", GraphQLServer::handleRest);
        server.createContext("/", ex -> send(ex, 200, "text/html; charset=utf-8", Playground.HTML.getBytes(StandardCharsets.UTF_8)));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("GraphQL server on http://localhost:" + PORT + "  (playground)  -- POST /graphql");
    }

    @SuppressWarnings("unchecked")
    static void handleGraphQL(HttpExchange ex, GraphQL graphQL) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                send(ex, 405, "application/json", "{\"error\":\"POST a JSON body {query, variables}\"}".getBytes());
                return;
            }
            Map<String, Object> body = JSON.readValue(ex.getRequestBody(), Map.class);
            Map<String, Object> variables = (Map<String, Object>) body.getOrDefault("variables", Map.of());
            MENU_QUERIES.set(0);
            long start = System.nanoTime();
            ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput()
                    .query((String) body.get("query"))
                    .variables(variables == null ? Map.of() : variables)
                    .operationName((String) body.get("operationName"))
                    .build());
            System.out.printf("[graphql] %.1f ms, menu table queried %d time(s), errors=%d%n",
                    (System.nanoTime() - start) / 1e6, MENU_QUERIES.get(), result.getErrors().size());
            // GraphQL answers 200 even when there are errors -- they're inside the body.
            send(ex, 200, "application/json", JSON.writeValueAsBytes(result.toSpecification()));
        } catch (Exception e) {
            send(ex, 400, "application/json", JSON.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    /** The REST way, for comparison: fixed shapes, one resource per URL. */
    static void handleRest(HttpExchange ex) throws IOException {
        String[] p = ex.getRequestURI().getPath().split("/"); // ["", "rest", "restaurants", id?, "menu"?]
        Object payload;
        if (p.length == 3) payload = RESTAURANTS;
        else if (p.length == 5 && p[4].equals("menu")) payload = MENU.stream().filter(m -> m.restaurantId().equals(p[3])).toList();
        else { send(ex, 404, "application/json", "{\"error\":\"not found\"}".getBytes()); return; }
        send(ex, 200, "application/json", JSON.writeValueAsBytes(payload));
    }

    static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}

final class Playground {
    static final String HTML = """
        <!doctype html><html><head><meta charset="utf-8"><title>QuickBite GraphQL</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0b0033;color:#fff;margin:0;padding:20px}
          h1{margin:0 0 12px;background:linear-gradient(90deg,#e535ab,#ffb300);-webkit-background-clip:text;color:transparent}
          .row{display:flex;gap:16px;flex-wrap:wrap}.col{flex:1;min-width:320px}
          textarea,pre{width:100%;box-sizing:border-box;background:#1d0b5a;color:#e0f7fa;border:2px solid #e535ab;border-radius:12px;padding:12px;font:13px ui-monospace,monospace}
          textarea{height:300px}#vars{height:70px}pre{min-height:390px;white-space:pre-wrap;border-color:#00e5ff}
          button{background:#e535ab;color:#fff;border:0;border-radius:10px;padding:8px 14px;font-weight:700;cursor:pointer;margin:0 6px 8px 0}
          .ex{background:#304ffe}
        </style></head><body>
        <h1>QuickBite GraphQL playground</h1>
        <button class="ex" data-q="home">Home screen</button><button class="ex" data-q="one">One restaurant</button>
        <button class="ex" data-q="mut">Add review</button><button class="ex" data-q="intro">Introspection</button>
        <div class="row"><div class="col">
          <textarea id="q"></textarea><textarea id="vars" placeholder='variables JSON, e.g. {"id":"r1"}'></textarea>
          <button id="run">Run ▶</button> <span id="meta"></span>
        </div><div class="col"><pre id="out"></pre></div></div>
        <script>
          const samples = {
            home: `query Home {\\n  restaurants(cuisine: "South Indian") {\\n    name\\n    rating\\n    menu(vegOnly: true) { name price }\\n  }\\n}`,
            one: `query One($id: ID!) {\\n  restaurant(id: $id) {\\n    name\\n    deliveryMinutes\\n    reviews(last: 2) { author rating comment }\\n  }\\n}`,
            mut: `mutation Review {\\n  addReview(input: {restaurantId: "r1", rating: 5, comment: "Loved it"}) {\\n    id author rating\\n  }\\n}`,
            intro: `{\\n  __type(name: "Restaurant") {\\n    fields { name type { name kind } }\\n  }\\n}`
          };
          const $ = id => document.getElementById(id);
          document.querySelectorAll('.ex').forEach(b => b.onclick = () => { $('q').value = samples[b.dataset.q]; $('vars').value = b.dataset.q === 'one' ? '{"id":"r1"}' : ''; });
          $('q').value = samples.home;
          $('run').onclick = async () => {
            const t = performance.now();
            const r = await fetch('/graphql', {method: 'POST', headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({query: $('q').value, variables: $('vars').value ? JSON.parse($('vars').value) : {}})});
            const text = await r.text();
            $('meta').textContent = `HTTP ${r.status} · ${text.length} bytes · ${Math.round(performance.now() - t)} ms`;
            $('out').textContent = JSON.stringify(JSON.parse(text), null, 2);
          };
        </script></body></html>
        """;
}

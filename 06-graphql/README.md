# Exercise 06 — GraphQL

**Time:** ~60 min  **Port:** 8086  **Needs:** Maven  **You'll learn:** schema-first APIs, queries/mutations/variables, field resolvers, over/under-fetching, the N+1 problem

## The story
The QuickBite **home screen** shows South Indian restaurants with their name, rating and two veg dishes with prices.
With the REST API:
```bash
curl -s localhost:8086/rest/restaurants | wc -c          # every field of every restaurant…
curl -s localhost:8086/rest/restaurants/r1/menu           # …then one call PER restaurant for menus
```
That's **over-fetching** (fields you don't need) *and* **under-fetching** (1 + N round trips). On a 3G phone in a lift, that hurts.

GraphQL flips it: **one endpoint**, and the *client* says exactly what shape it wants:
```graphql
query Home {
  restaurants(cuisine: "South Indian") {
    name
    rating
    menu(vegOnly: true) { name price }
  }
}
```

```mermaid
flowchart LR
    Q["restaurants(cuisine)"]:::q --> R1["Restaurant r1"]:::r
    Q --> R4["Restaurant r4"]:::r
    R1 --> M1["menu resolver (r1)"]:::m
    R4 --> M4["menu resolver (r4)"]:::m
    classDef q fill:#e535ab,color:#fff,stroke:#fff
    classDef r fill:#304ffe,color:#fff,stroke:#fff
    classDef m fill:#00c853,color:#fff,stroke:#fff
```
Each field can have its own resolver, which runs **only if the query asks for that field**.

## Run it
```bash
mvn -q compile exec:java          # first run downloads dependencies
```
Open **http://localhost:8086** — a mini playground with sample queries. Or use curl:
```bash
curl -s localhost:8086/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ restaurants { name cuisine } }"}'
```
Watch the server log: it prints how many times the menu "table" was queried per request.

## TODOs in `src/main/java/lab/GraphQLServer.java`
| # | Resolver | What |
|---|---|---|
| 1 | `Query.restaurants(cuisine)` | Filter by argument |
| 2 | `Query.restaurant(id)` | Return `null` when missing (nullable in schema) |
| 3 | `Restaurant.menu(vegOnly)` | A **field** resolver: uses `env.getSource()` (the parent) |
| 4 | `Mutation.addReview(input)` | Validate, write, return the new object |

`reviews` is already done — copy its pattern for TODO 3.

## Write these queries (in the playground)
1. Names and delivery times of **all** restaurants — nothing else.
2. Restaurant `r2` with its full menu, using a **variable** `$id` instead of hard-coding it.
3. The home-screen query above. How many bytes vs the REST calls? How many requests?
4. Use **aliases** to fetch `r1` and `r4` side by side in one query: `dosa: restaurant(id:"r1") {…} idli: restaurant(id:"r4") {…}`.
5. Define a **fragment** `CardFields on Restaurant { name rating deliveryMinutes }` and use it in two places.
6. Add a review with rating **7**. What HTTP status do you get? Where is the error?
7. Ask for a field that doesn't exist (`restaurants { owner }`). What happens, and *when* — before or after resolvers run?
8. Run the **Introspection** sample. How could a tool use this?

## Think & answer
1. Run query 3 and read the log: `menu table queried N times`. That's the **N+1 problem**. With 1000 restaurants on screen, what happens to the DB? How does a **DataLoader** fix it (batch all `restaurantId`s into one `WHERE restaurant_id IN (...)`)?
   - **What happens:** The server fires 1 query for the restaurants (`SELECT * FROM restaurants`), then for each of the 1,000 restaurants, GraphQL executes the `menu` field resolver individually (`SELECT * FROM menu WHERE restaurant_id = ?`). The database is crushed under 1,001 separate queries ($1 + N$).
   - **How DataLoader fixes it:** A DataLoader coalesces individual fetch requests across a single execution tick. Instead of running 1,000 separate queries, it batches all 1,000 IDs into a single SQL query: `SELECT * FROM menu WHERE restaurant_id IN (r1, r2, ... r1000)`, reducing 1,001 round-trips to just **2 queries** total.

2. GraphQL returns HTTP **200** even when a resolver failed. Why? What does that mean for your monitoring/alerting that only looks at status codes?
   - **Why 200:** GraphQL supports **partial execution**. If a query asks for both `profile` (succeeds) and `recommendations` (fails), GraphQL returns the available profile data under `data` and the error under `errors: [...]`. Since the network transport and request processing succeeded, the HTTP layer responds with `200 OK`.
   - **Monitoring Impact:** Traditional APM tools and load balancers monitoring 5xx/4xx HTTP status codes will show 100% green health even when users experience errors. Monitoring systems must inspect the JSON response body for the presence of the top-level `"errors"` array or hook into GraphQL execution instrumentation listeners.

3. REST GETs are cached by browsers and CDNs using the URL. Every GraphQL query is a POST to `/graphql`. How do teams cache GraphQL? (Search: persisted queries, APQ.)
   - Because GraphQL queries are typically sent via HTTP `POST` with dynamic query strings in the body, standard CDN and browser caches cannot cache them by URL.
   - **Automatic Persisted Queries (APQ):**
     1. The client sends an HTTP `GET /graphql?extensions={"persistedQuery":{"sha256Hash":"..."}}`.
     2. If the CDN or GraphQL gateway has that query hash in its cache, it serves the cached response directly over standard HTTP GET.
     3. On a cache miss, the client falls back to a `POST` supplying both the full query text and hash to warm the cache.
   - Teams also pre-register approved queries at build time (Persisted Queries), enforcing strict query allowlists in production.

4. A malicious client sends a query nested 50 levels deep. How do you protect the server? (Depth limits, complexity scoring, timeouts.)
   - **Query Depth Limiting:** Inspect the parsed Abstract Syntax Tree (AST) before execution; reject queries that exceed a maximum nesting depth (e.g. max depth 7).
   - **Query Complexity / Cost Analysis:** Assign cost weights to fields (e.g., scalar = 1 point, paginated list = 10 $\times$ count); reject queries that exceed an allowed computational budget (e.g. max cost 250).
   - **Timeouts:** Terminate execution if resolution takes longer than a safe threshold (e.g. 3–5 seconds).
   - **Production Whitelisting:** Disable arbitrary ad-hoc queries in production; only permit known persisted query hashes from official client applications.

5. Which would you choose for: a public API for third-party developers, an internal BFF for your own mobile app, service-to-service calls? Why?
   - **Public API for 3rd-party developers $\to$ REST:** Simple, universally understood, standard HTTP caching, standard error status codes, and easily rate-limited per endpoint.
   - **Internal Backend-For-Frontend (BFF) $\to$ GraphQL:** Perfect for mobile and web apps where screens require diverse data shapes; prevents over/under-fetching on mobile networks and lets frontend teams iterate without backend endpoint changes.
   - **Internal Service-to-Service $\to$ gRPC:** High throughput, compact binary Protobuf serialization, strict contract-first `.proto` definitions, and native HTTP/2 multiplexing/streaming with low CPU overhead.

## Stretch
- Add a `DataLoader` for `menu` (graphql-java ships `org.dataloader`) and make the log say `menu table queried 1 time(s)`.
- Add a `Subscription { orderStatus(orderId: ID!): String! }` — notice it needs a WebSocket or SSE underneath. Everything connects!

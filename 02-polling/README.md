# Exercise 02 — Short Polling vs Long Polling

**Time:** ~45 min  **Port:** 8082  **You'll learn:** why "just call the API every 2 seconds" is wasteful *and* slow, and how long polling fixes both

## The story
After placing an order, the QuickBite app shows a live tracker:
`PLACED → ACCEPTED → PREPARING → OUT_FOR_DELIVERY → DELIVERED`.
The server's "kitchen" moves each order forward on its own every ~3 s (randomised). How does the app find out?

```mermaid
sequenceDiagram
    participant App
    participant Server
    Note over App,Server: Short polling — ask on a timer
    App->>Server: GET /status
    Server-->>App: 200 PLACED (v1)
    App->>Server: GET /status   (2 s later)
    Server-->>App: 200 PLACED (v1)  ← wasted
    Note over App,Server: Long polling — server holds the request
    App->>Server: GET /status?sinceVersion=1
    Note right of Server: waits… kitchen moves order
    Server-->>App: 200 ACCEPTED (v2)  ← instantly
    App->>Server: GET /status?sinceVersion=2
```

## Run it
```bash
java TrackingServer.java     # terminal 1
java Check.java              # terminal 2 — checks TODO 1
```

## TODOs
| # | File | What |
|---|---|---|
| 1 | `TrackingServer.java` → `longPoll` | Park the request on a `Condition` until `version > sinceVersion` or timeout → 200 / **204** |
| 2 | `PollingClient.java` → `shortPoll` | Loop + sleep, count wasted calls |
| 3 | `PollingClient.java` → `longPoll` | Loop with **no sleep**, re-ask on 204 |

## The experiment (the whole point of this exercise)
Run each and fill the table from the scorecard:
```bash
java PollingClient.java short 500
java PollingClient.java short 2000
java PollingClient.java short 5000
java PollingClient.java long
```
| Mode | HTTP requests | Wasted | Avg notice delay | Missed steps |
|---|---|---|---|---|
| short 500 ms | ~24 | ~20 | ~250 ms | 0 |
| short 2000 ms | ~7 | ~3 | ~1050 ms | 0–1 |
| short 5000 ms | ~3 | 0 | ~2400 ms | 1–2 |
| long | 4 | 0 | ~10 ms | 0 |

Run `short 5000` a few times — you'll see `!! skipped` lines. The app **never showed** some statuses.

## Think & answer
1. Short polling has one knob (interval). What do you trade when you turn it down? When you turn it up?
   - **Turn down (e.g. 500 ms):** Low notice delay (fresher UI), but massive traffic amplification, high server CPU load, and lots of wasted requests.
   - **Turn up (e.g. 5000 ms):** Low server load, but high UI lag and missed intermediate status changes (e.g., skips `ACCEPTED` or `PREPARING`).
2. 1 million users each polling every 2 s — how many requests/second hit your servers, and how many are wasted?
   - **500,000 req/sec** hit the servers. If an order advances 4 times across a 15-minute delivery (450 polls per user), only 4 requests contain news. **>99% (~495,000 req/sec) are completely wasted empty checks.**
3. Long polling holds a request open for up to 30 s. Why did we use **virtual threads**? What breaks with a fixed pool of 8 threads and 9 waiting users?
   - In traditional platform threads with a fixed pool of 8, 8 parked long-polling clients monopolize all threads. The 9th request hangs or times out due to thread pool exhaustion.
   - Java 21 **virtual threads** unmount from their OS carrier thread while parked on `Condition.awaitNanos()`, using ~1 KB of memory and zero CPU, scaling to tens of thousands of concurrent waiting requests.
4. Why `signalAll()` and not `signal()`? (The checker has a test for this.)
   - Multiple clients/devices may track the same order simultaneously (customer phone, web browser, delivery rider, support agent). `signal()` only wakes up one arbitrary thread; `signalAll()` wakes up every client waiting on that order.
5. Why does the client send `sinceVersion` instead of the server remembering "what this client last saw"?
   - Keeps the server **stateless**. The request can be routed to any instance behind a load balancer without needing distributed session affinity. It also makes retries and network reconnects naturally idempotent.
6. Load balancers and proxies often kill idle connections after ~60 s. How does the timeout + 204 design survive that?
   - The server proactively completes the request before the proxy's idle timeout (e.g., after 25–30 s) with `204 No Content`. The client immediately re-establishes a fresh request, preventing abrupt TCP resets (`ECONNRESET` or 504 Gateway Timeout).
7. When is plain short polling still the **right** choice? (Hint: a report that takes 10 minutes to generate.)
   - When updates are very infrequent or take a long time (e.g. generating a 10-minute CSV export or batch video rendering), when the client is simple (e.g. a curl script or cron job), or when clients connect through aggressive corporate proxies that disallow long-held connections.

## Stretch
- Start 200 long-poll clients in parallel (a loop with `CompletableFuture`) and watch the server's thread count in `jconsole`. Then switch the executor to `Executors.newFixedThreadPool(8)` and repeat.
- Add **exponential backoff** to the short-poll client when the server returns 503.

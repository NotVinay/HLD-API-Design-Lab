# Exercise 08 — Capstone: pick the right API

**Time:** ~45 min (pairs work best)  **No code** — this is the interview skill.

In a system-design round nobody asks you to write `acceptKey()`. They ask: *"How does the app learn the rider moved?"* and watch whether you can name the options, pick one, and defend the trade-off. You've now built all of them, so you know what each one costs.

## Part A — Fill the comparison table from what you *saw* in exercises 01–07

| | REST | Short poll | Long poll | SSE | WebSocket | Webhook | GraphQL | gRPC |
|---|---|---|---|---|---|---|---|---|
| Who starts the conversation? | | | | | | | | |
| Direction of data | | | | | | | | |
| Connection: new per message / held open? | | | | | | | | |
| Wire format | | | | | | | | |
| Works from a browser out of the box? | | | | | | | | |
| Hardest thing to scale | | | | | | | | |
| One real product that uses it | | | | | | | | |

## Part B — Scenarios
For each: **pick one** (or a combo), give **one sentence why**, and name **one thing that can go wrong**.

1. The food-delivery app shows order status for the 30 minutes until delivery.
2. A trading dashboard shows 50 price updates/second for 20 stocks. Read-only.
3. A live quiz game: 500 players answer questions and see the leaderboard change instantly.
4. A payment gateway must tell the merchant that a refund has completed (could be 3 days later).
5. "Export all orders as CSV" takes ~10 minutes to generate.
6. The payments service calls the fraud-check service 20,000 times/second with a 50 ms budget.
7. Android, iOS and web home screens each need *different* fields from restaurants, offers and past orders.
8. Partner restaurants (thousands of different companies) integrate to update their menus.
9. A chatbot streams its answer token by token into the browser.
10. Two people edit the same document at the same time and see each other's cursors.
11. Your CI system must notify a team's Slack channel when a build fails.
12. The rider app sends its GPS position every 2 seconds while delivering.
13. A CLI script waits for a pull request's checks to finish before merging.
14. A browser tab must show "3 new notifications" within a few seconds — but your company's proxy kills connections held open > 30 s.

## Part C — Design QuickBite end to end
Draw one diagram of the whole system (customer app, restaurant tablet, rider app, backend services, payment gateway). Label **every arrow** with the API style you'd use and the direction of data. At minimum cover:
- browsing restaurants & menus
- placing an order
- paying, and learning the payment result
- restaurant accepting the order
- live order status for the customer
- rider ↔ customer chat
- rider GPS updates and live map for the customer
- order service ↔ delivery service ↔ pricing service (internal)
- notifying a partner restaurant's own POS system of a new order

Then answer:
1. Which arrow is the hardest to scale to 1M concurrent users, and why?
2. Where would you put an API gateway / BFF? What does it do?
3. Pick one arrow and describe its failure mode and your retry/idempotency strategy.

## Part D — 60-second explanations (practise out loud)
- "WebSocket vs SSE — when would you choose SSE?"
- "Why not just poll?"
- "How do you make a webhook receiver safe?"
- "When is GraphQL a bad idea?"
- "Why do companies use gRPC internally but REST externally?"

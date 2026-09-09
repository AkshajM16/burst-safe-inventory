# Burst-Safe Inventory Reservation Service

A controlled PostgreSQL concurrency experiment in Java 21/Spring Boot. The same HTTP API can run a realistic read/check/write implementation that oversells (`naive`) or a database-atomic implementation that remains correct across process boundaries (`safe`). This is intentionally an inventory primitive, not an e-commerce application.

> **Portfolio status:** complete, tested, benchmarked, and ready to run locally. The intentionally unsafe V1 is isolated behind an explicit startup guard.

## Run it

Requirements: Docker Desktop/Engine with Compose, Python 3, and `curl`. Java and Maven are provided by containers.

```bash
make start                 # PostgreSQL + safe service on :8080
make test                  # unit + PostgreSQL-backed integration tests
make compare               # V1/V2 sweep and SVG/CSV/JSON output
make replicas              # repeated V2 test through two app processes
make failure-test          # restart/replay and invalid-input checks
make stop
```

`make compare` deliberately starts unsafe V1 on port 8081 with a 10 ms test-aid delay. It writes timestamped data under the gitignored `results/` directory; `results/LATEST` identifies the newest comparison. Use `NAIVE_DELAY_MS=0 make compare` for an unassisted run. Never expose the naive service: it refuses to start unless both `INVENTORY_MODE=naive` and `ALLOW_UNSAFE_MODE=true` are explicit.

## API

JSON request fields accept camelCase or the documented snake_case aliases.

| Operation | Request | Success / stable outcomes |
|---|---|---|
| `POST /items` | `{"sku":"hot-widget","initial_stock":100}` | `201` item |
| `GET /items/{id}` | — | `200` with stock and committed totals; `404 ITEM_NOT_FOUND` |
| `POST /items/{id}/reserve` | `{"reservation_id":"<uuid>","quantity":1}` | `201 SUCCEEDED`; `409 SOLD_OUT`; `200` replay with original semantic `status`; `409 IDEMPOTENCY_CONFLICT` |
| Any endpoint | malformed/invalid input | `400 INVALID_INPUT` |
| Database/pool unavailable | — | `503 OVERLOADED` |
| Unexpected fault | — | `500 INTERNAL_ERROR` |

Example:

```bash
item_id=$(curl -s -H 'Content-Type: application/json' \
  -d '{"sku":"demo","initial_stock":1}' http://localhost:8080/items | \
  python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -i -H 'Content-Type: application/json' \
  -d '{"reservation_id":"8ecae2c8-facd-41e8-a46a-70b45d3ca759","quantity":1}' \
  "http://localhost:8080/items/$item_id/reserve"
```

`X-Request-Id` is accepted and returned. Logs are JSON and include MDC `requestId` and `reservationId`; no request body or credentials are logged. Readiness is `GET /actuator/health/readiness`.

## Schema and invariants

The Flyway migration creates `items` and `reservations`. PostgreSQL enforces positive initial stock, nonnegative current stock, valid reservation quantities/status combinations, item foreign keys, unique SKU, and a global primary key on `reservation_id`.

For each item with initial inventory `I`, safe mode maintains:

1. `stock >= 0`.
2. `SUM(reservations.reserved_qty) <= I`.
3. `stock + SUM(reservations.reserved_qty) = I`.
4. One `reservation_id` identifies at most one row and one decrement.
5. A replay with the same item and quantity returns the committed semantic result; different payload returns `IDEMPOTENCY_CONFLICT`.

The database constraints cover row-level validity. The V2 conditional update and transaction establish the cross-row conservation invariant.

## Why V1 fails

V1 executes `SELECT stock`, checks the value in Java, then later writes the computed absolute value with `UPDATE items SET stock = ?`. The operations are in transactions, but PostgreSQL `READ COMMITTED` does not make that application-level decision atomic.

```mermaid
sequenceDiagram
  participant A as Request A
  participant B as Request B
  participant DB as PostgreSQL (stock=1)
  A->>DB: SELECT stock → 1
  B->>DB: SELECT stock → 1
  A->>DB: UPDATE stock=0; INSERT success A
  A->>DB: COMMIT
  B->>DB: UPDATE stock=0; INSERT success B
  B->>DB: COMMIT
  Note over DB: stock=0, reserved_qty=2: conservation violated
```

The optional `NAIVE_DELAY_MS` occurs after the read and is labeled/tested only as a synchronization aid. It is not the bug. On the measured machine, V1 also failed in all three 100-worker rounds with `NAIVE_DELAY_MS=0`.

## Why V2 is safe and idempotent

V2 makes a reservation claim with `INSERT ... ON CONFLICT DO NOTHING`. PostgreSQL's unique index arbitrates concurrent copies of the key; a loser waits for the winner and then reads its final result. The winner performs:

```sql
UPDATE items
SET stock = stock - :quantity
WHERE id = :item_id AND stock >= :quantity;
```

The claim, conditional decrement, and final reservation status commit in one transaction. A zero-row update leaves a durable `SOLD_OUT` result. No local lock is involved, so two JVMs share the same guarantee.

```mermaid
sequenceDiagram
  participant A as Request A / replica 1
  participant B as Retry / replica 2
  participant DB as PostgreSQL
  A->>DB: BEGIN; INSERT idempotency claim
  B->>DB: INSERT same reservation_id
  Note over B,DB: unique index waits for A
  A->>DB: UPDATE ... WHERE stock >= quantity
  A->>DB: mark SUCCEEDED or keep SOLD_OUT; COMMIT
  DB-->>B: ON CONFLICT → 0 rows
  B->>DB: SELECT original result; COMMIT
  B-->>B: return replay=true, same status
```

## Stress methodology

`stress/stress.py` uses only the Python standard library. For every trial it creates a fresh item, precomputes deterministic UUIDs, queues counted requests behind a simultaneous release gate, and records every response and latency. Every tenth logical request retries the previous key. It then fetches server-side totals and evaluates the invariants. `stress/compare.py` uses equal inventory, request count, duplicate pattern, pool configuration, and logical trace shape for both modes. Each concurrency level gets a fresh item; no result is fabricated or postprocessed away.

Recorded columns include successful unique keys, committed quantity, oversold successes, duplicate side effects, invariant gap/violations, requests/s, p50/p95/p99, HTTP/DB errors, transport timeouts, replicas, and test-aid delay. Raw samples are JSON, summaries are CSV, run context is JSON, and `plot.py` emits dependency-free SVG.

## Measured results

The checked-in run at `benchmarks/20260909T082640Z` used PostgreSQL 16, Java 21 containers, one app process with a 20-connection pool, 100 initial units, 150 requests, and one sweep trial per point on an Apple Silicon Mac under Docker Desktop. These are local experiment results, not production capacity claims.

| Workers | V1 unique successes | V1 invariant violations | V1 p95 ms | V2 unique successes | V2 invariant violations | V2 p95 ms |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 0 | 22.69 | 100 | 0 | 3.14 |
| 10 | 136 | 2 | 20.70 | 100 | 0 | 16.03 |
| 50 | 136 | 2 | 45.77 | 100 | 0 | 83.88 |
| 100 | 136 | 2 | 82.32 | 100 | 0 | 101.67 |
| 250 | 136 | 2 | 108.02 | 100 | 0 | 123.42 |
| 500 | 136 | 2 | 107.92 | 100 | 0 | 122.63 |

The V1 sweep used the explicit 10 ms aid. Its violation was also unassisted: three of three 100-worker trials committed 136 unique units against 100 initial units, with conservation gaps of -127, -129, and -129. A separate V2 run sent five rounds each at 100 and 250 workers across two service processes: all ten rounds had zero invariant violations, zero duplicate side effects, and zero server/transport errors. See raw data, metadata, and the generated [comparison.svg](benchmarks/20260909T082640Z/comparison.svg).

Correctness—not a speedup—is the result. V2 p95 was higher at most burst levels because contenders serialize on the hot database row; this is the expected cost of preserving one scarce counter.

## Tests and operational controls

- `SafeReservationIntegrationTest`: 200 simultaneous attempts for 100 units, 40 concurrent copies of one key, payload conflict, replay consistency, and missing item.
- `NaiveReservationIntegrationTest`: controlled one-unit oversell proof.
- `make stress`: ten repeated safe rounds.
- `make replicas`: two JVMs, five rounds at 100 and 250 workers.
- `make failure-test`: commit, service restart, identical replay, ambiguous timeout retry, zero quantity, and missing item.
- Hikari limits: `DB_POOL_SIZE` (20), `DB_CONNECTION_TIMEOUT_MS` (2000); Tomcat: `HTTP_MAX_THREADS` (200), `HTTP_CONNECTION_TIMEOUT` (3s).

## Limitations

- This is a single hot-row design. Correctness scales across replicas; throughput for one item is intentionally serialized by PostgreSQL.
- Reservation cancellation, expiry, authentication, rate limiting, multi-item orders, and archival are out of scope.
- Idempotency records have no retention policy; a production system needs lifecycle rules that preserve replay guarantees.
- Client disconnects cannot prove whether a commit happened. The caller must retry the same key; the durable record resolves the ambiguity.
- The harness is a correctness-oriented local generator, not a calibrated distributed load-testing system. Results vary with Docker, CPU scheduling, logging, and connection limits.

## Resume bullet

Built a Java 21/Spring Boot/PostgreSQL reservation service using conditional SQL and database-backed idempotency; reproduced V1 overselling in 3/3 unassisted 100-worker trials and verified zero invariant violations across 10 two-replica V2 burst trials (100–250 workers), with raw JSON/CSV and latency/correctness plots.

## Publish this repository on GitHub

Suggested repository name: **`burst-safe-inventory`**

Suggested description: **Concurrency-safe inventory reservations in Java and PostgreSQL, with reproducible race tests, idempotency, and multi-replica benchmarks.**

Suggested topics: `java`, `spring-boot`, `postgresql`, `concurrency`, `distributed-systems`, `idempotency`, `docker`, `jdbc`, `load-testing`, `portfolio-project`.

The repository is initialized on the `main` branch. To publish it with the GitHub CLI:

```bash
gh auth login
gh repo create burst-safe-inventory \
  --public \
  --description "Concurrency-safe inventory reservations in Java and PostgreSQL, with reproducible race tests, idempotency, and multi-replica benchmarks." \
  --source=. \
  --remote=origin \
  --push
gh repo edit \
  --add-topic java \
  --add-topic spring-boot \
  --add-topic postgresql \
  --add-topic concurrency \
  --add-topic distributed-systems \
  --add-topic idempotency \
  --add-topic docker \
  --add-topic jdbc \
  --add-topic load-testing \
  --add-topic portfolio-project
```

Alternatively, create an empty public repository named `burst-safe-inventory` on GitHub, then run:

```bash
git remote add origin https://github.com/YOUR_USERNAME/burst-safe-inventory.git
git push -u origin main
```

After publishing, confirm that the **CI** workflow passes under the repository's Actions tab. Optionally add a social preview using `benchmarks/20260909T082640Z/comparison.svg` converted to PNG, pin the repository on your profile, and replace `YOUR_USERNAME` anywhere you add a personal link.

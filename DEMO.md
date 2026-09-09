# Five-minute demo

## 1. Establish the experiment (30 seconds)

Explain the invariant: `remaining stock + committed reserved quantity = initial stock`. V1 and V2 expose the same API and share PostgreSQL; only transaction semantics differ.

## 2. Start and prove health (30 seconds)

```bash
make start
curl -s http://localhost:8080/actuator/health/readiness
```

Expected: `{"status":"UP"}`. Point out that naive mode is absent and cannot start accidentally without `ALLOW_UNSAFE_MODE=true`.

## 3. Reproduce V1, compare V2 (2 minutes)

```bash
make compare
cat results/LATEST
```

Open the generated `comparison.svg` in that directory. At burst concurrency V1 normally reports more than 100 successful unique reservations and a nonzero/negative conservation gap. V2 must report exactly 100 committed units and zero violations. If presenting the checked-in run, use `benchmarks/20260909T082640Z`.

Show the concrete interleaving in the README: two callers read 1; both write 0; both insert successful reservations. Then show V2's `UPDATE ... WHERE stock >= ?` and unique `reservation_id` claim. For the checked-in run, use `benchmarks/20260909T082640Z`.

## 4. Demonstrate ambiguous retry and restart (1 minute)

```bash
make failure-test
```

The script creates and commits a reservation, restarts the safe JVM, retries the same key, and asserts `replay=true` with the original `SUCCEEDED` result. It also verifies stable 400 and 404 paths.

## 5. Prove process independence (1 minute)

```bash
make replicas
```

Requests alternate between ports 8080 and 8082 while both JVMs use one PostgreSQL database. All ten checked trials must finish with zero invariant violations and zero duplicate side effects.

## Close

The claim is not that V2 is universally faster. It is that PostgreSQL turns stock decrement plus idempotent side effect into one durable state transition, so correctness survives bursts, retries, restarts, and multiple service processes.

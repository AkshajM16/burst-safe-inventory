# Contributing

Thanks for improving the experiment. Keep changes focused on concurrency correctness, reproducibility, or documentation rather than expanding it into a general commerce system.

## Development workflow

1. Create a branch from `main`.
2. Make a focused change and add a test for behavioral changes.
3. Run `make test`.
4. If transaction behavior changed, run `make compare` and `make replicas`.
5. Open a pull request describing the invariant affected and the evidence collected.

Never weaken the safe-mode invariants to improve benchmark numbers. Do not commit generated `results/`; publish a deliberately selected baseline under `benchmarks/` only when the environment, commands, and interpretation are documented.

## Commit and pull-request guidance

- Use an imperative subject such as `Handle concurrent duplicate reservation keys`.
- Keep V1 intentionally unsafe but realistic and clearly labeled.
- Keep the critical transaction as inspectable SQL.
- Do not add credentials, customer data, or proprietary benchmark traces.
- Note schema compatibility and migration implications for database changes.

# Security policy

This is a portfolio concurrency experiment, not a production-ready commerce service. It intentionally includes a vulnerable `naive` mode that must never be exposed to untrusted traffic.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting feature instead of opening a public issue. Include the affected mode, reproduction steps, impact, and any proposed mitigation. Do not include real credentials or customer data.

## Supported version

Only the latest commit on `main` is maintained. Safe mode is the only mode intended for normal use. Authentication, authorization, rate limiting, TLS termination, idempotency-record retention, and abuse protection remain outside this project's scope.

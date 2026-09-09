#!/usr/bin/env python3
"""Dependency-free burst and benchmark harness for the inventory HTTP API."""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import datetime as dt
import json
import os
import platform
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class Sample:
    request_no: int
    reservation_id: str
    http_status: int
    semantic_status: str
    replay: bool
    latency_ms: float
    error: str


def request_json(method: str, url: str, body=None, timeout=8.0):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        payload = json.loads(error.read() or b"{}")
        return error.code, payload


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * p)))
    return ordered[index]


def run_trial(base_urls, mode, concurrency, inventory, attempts, duplicate_every,
              timeout, trial, output_dir, deterministic_delay, key_salt):
    sku = f"bench-{mode}-c{concurrency}-t{trial}-{uuid.uuid4().hex[:10]}"
    status, item = request_json("POST", base_urls[0] + "/items",
                                {"sku": sku, "initialStock": inventory}, timeout)
    if status != 201:
        raise RuntimeError(f"item creation failed ({status}): {item}")
    item_id = item["id"]
    # Mode is part of the namespace because reservation_id is globally unique in
    # the shared comparison database. The logical trace remains identical.
    namespace = uuid.uuid5(uuid.NAMESPACE_DNS, f"burst-safe-{key_salt}-{mode}-c{concurrency}-t{trial}")
    ids = []
    for i in range(attempts):
        key_no = i - 1 if duplicate_every and i % duplicate_every == 0 else i
        ids.append(str(uuid.uuid5(namespace, str(key_no))))

    gate = threading.Event()

    def reserve(i):
        gate.wait()
        started = time.perf_counter()
        try:
            code, payload = request_json("POST", base_urls[i % len(base_urls)] + f"/items/{item_id}/reserve",
                                         {"reservationId": ids[i], "quantity": 1}, timeout)
            return Sample(i, ids[i], code, payload.get("status", payload.get("code", "UNKNOWN")),
                          bool(payload.get("replay", False)),
                          (time.perf_counter() - started) * 1000, "")
        except Exception as error:  # timeout, reset, DNS, and malformed response are recorded
            return Sample(i, ids[i], 0, "TRANSPORT_ERROR", False,
                          (time.perf_counter() - started) * 1000, type(error).__name__)

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(reserve, i) for i in range(attempts)]
        gate.set()
        samples = [future.result() for future in futures]
    elapsed = time.perf_counter() - started
    _, final_item = request_json("GET", base_urls[0] + f"/items/{item_id}", timeout)

    successful_keys = {s.reservation_id for s in samples if s.semantic_status == "SUCCEEDED"}
    latencies = [s.latency_ms for s in samples]
    unique_successes = len(successful_keys)
    reserved = int(final_item["reservedQuantity"])
    remaining = int(final_item["stock"])
    invariant_gap = inventory - (remaining + reserved)
    result = {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mode": mode, "trial": trial, "concurrency": concurrency,
        "inventory": inventory, "attempts": attempts, "duplicate_every": duplicate_every,
        "replicas": len(base_urls), "item_id": item_id,
        "successful_unique_reservations": unique_successes,
        "committed_reserved_quantity": reserved, "remaining_stock": remaining,
        "invalid_oversold_success_count": max(0, unique_successes - inventory),
        "duplicate_side_effect_count": max(0, reserved - unique_successes),
        "invariant_gap": invariant_gap,
        "invariant_violations": int(remaining < 0) + int(reserved > inventory) + int(invariant_gap != 0),
        "requests_per_second": attempts / elapsed,
        "p50_ms": percentile(latencies, .50), "p95_ms": percentile(latencies, .95),
        "p99_ms": percentile(latencies, .99),
        "db_or_http_errors": sum(s.http_status >= 500 or s.http_status == 0 for s in samples),
        "transport_errors": sum(s.http_status == 0 for s in samples),
        "elapsed_seconds": elapsed, "test_aid_delay_ms": deterministic_delay,
    }
    raw_path = output_dir / f"raw-{mode}-c{concurrency}-t{trial}.json"
    raw_path.write_text(json.dumps({"summary": result, "samples": [asdict(s) for s in samples]}, indent=2))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["safe", "naive"], required=True)
    parser.add_argument("--urls", default="http://localhost:8080",
                        help="comma-separated replica base URLs")
    parser.add_argument("--concurrency", default="1,10,50,100,250,500")
    parser.add_argument("--inventory", type=int, default=100)
    parser.add_argument("--attempts", type=int, default=150)
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--duplicate-every", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=8.0)
    parser.add_argument("--output-dir")
    parser.add_argument("--assert-safe", action="store_true")
    parser.add_argument("--test-aid-delay-ms", type=int, default=0)
    parser.add_argument("--key-salt", default=uuid.uuid4().hex,
                        help="unique run salt; set explicitly to reproduce IDs")
    args = parser.parse_args()

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_dir = Path(args.output_dir or f"results/{stamp}-{args.mode}")
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for trial in range(1, args.trials + 1):
        for concurrency in [int(value) for value in args.concurrency.split(",")]:
            row = run_trial(args.urls.split(","), args.mode, concurrency, args.inventory,
                            args.attempts, args.duplicate_every, args.timeout, trial,
                            output_dir, args.test_aid_delay_ms, args.key_salt)
            rows.append(row)
            print(json.dumps(row, sort_keys=True))

    with (output_dir / "summary.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=rows[0].keys())
        writer.writeheader(); writer.writerows(rows)
    metadata = {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "platform": platform.platform(), "python": platform.python_version(),
        "cpu_count": os.cpu_count(), "arguments": vars(args),
        "service_config": {
            "db_pool_size": os.getenv("DB_POOL_SIZE", "20 (compose default)"),
            "db_connection_timeout_ms": os.getenv("DB_CONNECTION_TIMEOUT_MS", "2000 (compose default)"),
            "http_max_threads": os.getenv("HTTP_MAX_THREADS", "200 (application default)"),
            "postgres_image": "postgres:16-alpine", "java": "21"
        },
    }
    (output_dir / "metadata.json").write_text(json.dumps(metadata, indent=2, default=str))
    print(f"wrote {output_dir}")
    if args.assert_safe and any(row["invariant_violations"] for row in rows):
        raise SystemExit("SAFE MODE INVARIANT FAILURE")


if __name__ == "__main__":
    main()

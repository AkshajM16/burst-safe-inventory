#!/usr/bin/env python3
"""Run the same trace against V1 and V2 and produce combined CSV/JSON metadata."""
import argparse
import csv
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--concurrency", default="1,10,50,100,250,500")
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--attempts", type=int, default=150)
    parser.add_argument("--inventory", type=int, default=100)
    parser.add_argument("--naive-url", default="http://localhost:8081")
    parser.add_argument("--safe-urls", default="http://localhost:8080")
    parser.add_argument("--naive-delay-ms", type=int, default=10)
    args = parser.parse_args()
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    root = Path("results") / stamp
    for mode, urls in (("naive", args.naive_url), ("safe", args.safe_urls)):
        command = [sys.executable, "stress/stress.py", "--mode", mode, "--urls", urls,
                   "--concurrency", args.concurrency, "--trials", str(args.trials),
                   "--attempts", str(args.attempts), "--inventory", str(args.inventory),
                   "--output-dir", str(root / mode), "--key-salt", stamp]
        if mode == "safe": command.append("--assert-safe")
        if mode == "naive": command += ["--test-aid-delay-ms", str(args.naive_delay_ms)]
        subprocess.run(command, check=True)
    rows = []
    for mode in ("naive", "safe"):
        with (root / mode / "summary.csv").open() as handle:
            rows.extend(csv.DictReader(handle))
    with (root / "comparison.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    subprocess.run([sys.executable, "stress/plot.py", str(root / "comparison.csv"), str(root / "comparison.svg")], check=True)
    (Path("results") / "LATEST").write_text(str(root) + "\n")
    print(f"comparison: {root}")


if __name__ == "__main__": main()

#!/usr/bin/env python3
"""Render a dependency-free SVG comparison from benchmark summary CSV."""
import csv, html, sys
from pathlib import Path

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
rows = list(csv.DictReader(src.open()))
width, height, margin = 1000, 560, 70
levels = sorted({int(r["concurrency"]) for r in rows})
max_latency = max(float(r["p95_ms"]) for r in rows) or 1
colors = {"naive": "#dc2626", "safe": "#16a34a"}
parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
         '<rect width="100%" height="100%" fill="#fff"/>',
         '<style>text{font-family:system-ui,sans-serif;fill:#172033}.title{font-size:22px;font-weight:700}.label{font-size:13px}</style>',
         '<text x="70" y="35" class="title">Burst-Safe Inventory: correctness and p95 latency</text>']

def x(i): return margin + i * ((width - 2 * margin) / max(1, len(levels) - 1))
def y_latency(v): return 260 - float(v) / max_latency * 180

parts += ['<line x1="70" y1="260" x2="930" y2="260" stroke="#94a3b8"/>',
          '<text x="10" y="150" class="label" transform="rotate(-90 10 150)">p95 latency (ms)</text>']
for i, level in enumerate(levels):
    parts.append(f'<text x="{x(i)}" y="282" text-anchor="middle" class="label">{level}</text>')
for mode in ("naive", "safe"):
    data = [next((r for r in rows if r["mode"] == mode and int(r["concurrency"]) == level), None) for level in levels]
    points = " ".join(f'{x(i)},{y_latency(r["p95_ms"])}' for i, r in enumerate(data) if r)
    parts.append(f'<polyline points="{points}" fill="none" stroke="{colors[mode]}" stroke-width="3"/>')

parts += ['<line x1="70" y1="500" x2="930" y2="500" stroke="#94a3b8"/>',
          '<text x="10" y="410" class="label" transform="rotate(-90 10 410)">invariant violations</text>']
max_v = max(int(r["invariant_violations"]) for r in rows) or 1
for i, level in enumerate(levels):
    for offset, mode in ((-11, "naive"), (11, "safe")):
        r = next((r for r in rows if r["mode"] == mode and int(r["concurrency"]) == level), None)
        if r:
            v = int(r["invariant_violations"]); bar = v / max_v * 170
            parts.append(f'<rect x="{x(i)+offset-9}" y="{500-bar}" width="18" height="{bar}" fill="{colors[mode]}"/>')
parts += [f'<circle cx="760" cy="35" r="6" fill="{colors["naive"]}"/><text x="772" y="40" class="label">V1 naive</text>',
          f'<circle cx="855" cy="35" r="6" fill="{colors["safe"]}"/><text x="867" y="40" class="label">V2 safe</text>',
          '<text x="500" y="545" text-anchor="middle" class="label">concurrent workers</text>', '</svg>']
dst.write_text("".join(parts))
print(f"wrote {dst}")

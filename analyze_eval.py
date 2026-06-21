# -*- coding: utf-8 -*-
import csv
import re
import statistics
from collections import defaultdict
from pathlib import Path

BASE = Path("E:/new_knowledge_system/knowledge_system")
rows = list(csv.DictReader((BASE / "rag_eval_questions.csv").open(encoding="utf-8")))
lines = (BASE / "eval_results.txt").read_text(encoding="utf-16").splitlines()

parsed = []
for line in lines:
    if line.startswith("[ERROR]"):
        q = line.split(" -> ")[0].replace("[ERROR] ", "")
        parsed.append({"status": "ERROR", "precision": 0.0, "latency": 0.0, "question": q})
    else:
        m = re.match(
            r"\[\d+/126\] (HIT|MISS) \| chunks=\d+ \| precision=([\d.]+)% \| ([\d.]+)ms",
            line,
        )
        if m:
            parsed.append(
                {
                    "status": m.group(1),
                    "precision": float(m.group(2)),
                    "latency": float(m.group(3)),
                }
            )

by_file = defaultdict(lambda: {"total": 0, "hit": 0, "miss": 0, "error": 0, "prec_sum": 0.0})
for i, row in enumerate(rows):
    r = parsed[i]
    f = row["expected_file"]
    d = by_file[f]
    d["total"] += 1
    d["prec_sum"] += r["precision"]
    if r["status"] == "HIT":
        d["hit"] += 1
    elif r["status"] == "ERROR":
        d["error"] += 1
    else:
        d["miss"] += 1

print("=== By expected_file ===")
for f in sorted(by_file):
    d = by_file[f]
    print(
        "{:<22} n={:2d} hit={:2d} miss={:2d} err={} recall={:5.1f}% avgP@5={:5.1f}%".format(
            f,
            d["total"],
            d["hit"],
            d["miss"],
            d["error"],
            d["hit"] / d["total"] * 100,
            d["prec_sum"] / d["total"],
        )
    )

lat_cold = [p["latency"] for p in parsed if p["latency"] > 100]
lat_warm = [p["latency"] for p in parsed if 0 < p["latency"] <= 100]

def p95(vals):
    s = sorted(vals)
    return s[max(0, min(int(len(s) * 0.95) - 1, len(s) - 1))]

print("\n=== Latency (HTTP /vector/search) ===")
print("cold (>100ms): n={} avg={:.0f} p95={:.0f}".format(len(lat_cold), statistics.mean(lat_cold), p95(lat_cold)))
if lat_warm:
    print("warm (<=100ms): n={} avg={:.0f}".format(len(lat_warm), statistics.mean(lat_warm)))

hits = [p["precision"] for p in parsed if p["status"] == "HIT"]
print("\n=== HIT precision distribution ===")
for th in [100, 80, 60, 40, 20]:
    print(">={}%: {}".format(th, sum(1 for x in hits if x >= th)))
print("avg precision on HITs only: {:.1f}%".format(statistics.mean(hits)))

print("\n=== MISS blocks ===")
miss_ranges = []
start = None
for i, p in enumerate(parsed, 1):
    if p["status"] != "HIT":
        if start is None:
            start = i
    else:
        if start is not None:
            miss_ranges.append((start, i - 1))
            start = None
if start is not None:
    miss_ranges.append((start, len(parsed)))
for a, b in miss_ranges:
    files = sorted({rows[i - 1]["expected_file"] for i in range(a, b + 1)})
    print("Q{}-{} -> {}".format(a, b, ", ".join(files)))

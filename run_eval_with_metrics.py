# -*- coding: utf-8 -*-
"""Run rag_eval_questions.csv and report Precision@5, Recall@5, P95 latency."""
import csv
import statistics
import time

import requests

API_URL = "http://localhost:8080/vector/search"
CSV_PATH = "E:/new_knowledge_system/knowledge_system/rag_eval_questions.csv"
TOP_K = 5


def p95(values):
    if not values:
        return 0.0
    sorted_vals = sorted(values)
    idx = int(len(sorted_vals) * 0.95) - 1
    idx = max(0, min(idx, len(sorted_vals) - 1))
    return sorted_vals[idx]


def contains_keywords(text, keywords):
    for keyword in keywords.split(";"):
        if keyword.strip() and keyword.strip() in (text or ""):
            return True
    return False


def is_relevant(chunk, expected_file, expected_keywords):
    file_name = chunk.get("fileName", "")
    content = chunk.get("content", "")
    return expected_file in file_name or contains_keywords(content, expected_keywords)


def call_search(question):
    start = time.perf_counter()
    response = requests.post(
        API_URL,
        data=question.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        timeout=120,
    )
    elapsed_ms = (time.perf_counter() - start) * 1000
    response.raise_for_status()
    result = response.json()
    chunks = result if isinstance(result, list) else result.get("chunks", [])
    return chunks[:TOP_K], elapsed_ms


def main():
    total = 0
    hit = 0
    total_precision = 0
    latencies = []
    miss_count = 0

    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            question = row["question"]
            expected_file = row["expected_file"]
            expected_keywords = row["expected_keywords"]

            try:
                chunks, elapsed_ms = call_search(question)
            except Exception as exc:
                print("[ERROR] {} -> {}".format(question, exc))
                latencies.append(0.0)
                total += 1
                miss_count += 1
                continue

            latencies.append(elapsed_ms)
            relevant = sum(
                1 for c in chunks if is_relevant(c, expected_file, expected_keywords)
            )
            success = relevant > 0
            precision = relevant / len(chunks) if chunks else 0.0

            total += 1
            total_precision += precision
            if success:
                hit += 1
            else:
                miss_count += 1

            print("[{}/126] {} | chunks={} | precision={:.1f}% | {:.0f}ms".format(
                total,
                "HIT" if success else "MISS",
                len(chunks),
                precision * 100,
                elapsed_ms,
            ))

    print("\n========== 评测结果 ==========")
    print("样本数: {}".format(total))
    print("Recall@{}: {:.2f}% ({}/{})".format(TOP_K, hit / total * 100 if total else 0, hit, total))
    print("Average Precision@{}: {:.2f}%".format(TOP_K, total_precision / total * 100 if total else 0))
    print("Miss: {}".format(miss_count))

    valid_latencies = [v for v in latencies if v > 0]
    if valid_latencies:
        print("\n========== 检索延迟 (HTTP, 不含 LLM) ==========")
        print("平均: {:.2f} ms".format(statistics.mean(valid_latencies)))
        print("P50: {:.2f} ms".format(p95(valid_latencies[: int(len(valid_latencies) * 0.5) or 1])))
        print("P95: {:.2f} ms".format(p95(valid_latencies)))
        print("最大: {:.2f} ms".format(max(valid_latencies)))
        print("最小: {:.2f} ms".format(min(valid_latencies)))


if __name__ == "__main__":
    main()

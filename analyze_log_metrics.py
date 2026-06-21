import re
from pathlib import Path
from statistics import mean

LOG_PATH = Path("E:/new_knowledge_system/knowledge_system/logs/app.log")

SEARCH_COST_MAX_MS = 60_000
LLM_COST_MAX_MS = 60_000
RAG_TOTAL_COST_MAX_MS = 120_000


def extract_numbers(pattern, text):
    return [int(x) for x in re.findall(pattern, text)]


def filter_outliers(values, max_ms):
    return [v for v in values if v <= max_ms]


def percent(part, total):
    if total == 0:
        return "0.00%"
    return f"{part / total * 100:.2f}%"


def avg(values):
    if not values:
        return 0
    return round(mean(values), 2)


def p95(values):
    if not values:
        return 0
    values = sorted(values)
    index = int(len(values) * 0.95) - 1
    index = max(0, min(index, len(values) - 1))
    return values[index]


def main():
    if not LOG_PATH.exists():
        print(f"找不到日志文件: {LOG_PATH.resolve()}")
        print("请确认 application.properties 里有 logging.file.name=logs/app.log，并且重启过项目。")
        return

    text = LOG_PATH.read_text(encoding="utf-8", errors="ignore")

    search_costs_raw = extract_numbers(r"METRIC searchCostMs=(\d+)", text)
    llm_costs_raw = extract_numbers(r"METRIC llmCostMs=(\d+)", text)
    rag_total_costs_raw = extract_numbers(r"METRIC ragTotalCostMs=(\d+)", text)

    search_costs = filter_outliers(search_costs_raw, SEARCH_COST_MAX_MS)
    llm_costs = filter_outliers(llm_costs_raw, LLM_COST_MAX_MS)
    rag_total_costs = filter_outliers(rag_total_costs_raw, RAG_TOTAL_COST_MAX_MS)

    embedding_hit = len(re.findall(r"METRIC embeddingCacheHit=1", text))
    embedding_miss = len(re.findall(r"METRIC embeddingCacheMiss=1", text))

    search_hit = len(re.findall(r"METRIC searchCacheHit=1", text))
    search_miss = len(re.findall(r"METRIC searchCacheMiss=1", text))

    rag_reject = len(re.findall(r"METRIC ragReject=1", text))

    print("========== RAG 日志指标统计 ==========")

    print("\n【检索耗时】")
    print(f"search 请求数(原始): {len(search_costs_raw)}")
    print(f"search 请求数(过滤后, ≤{SEARCH_COST_MAX_MS}ms): {len(search_costs)}")
    print(f"过滤异常值: {len(search_costs_raw) - len(search_costs)}")
    print(f"平均 searchCostMs: {avg(search_costs)} ms")
    print(f"P95 searchCostMs: {p95(search_costs)} ms")

    print("\n【LLM 耗时】")
    print(f"LLM 调用数(原始): {len(llm_costs_raw)}")
    print(f"LLM 调用数(过滤后, ≤{LLM_COST_MAX_MS}ms): {len(llm_costs)}")
    print(f"过滤异常值: {len(llm_costs_raw) - len(llm_costs)}")
    print(f"平均 llmCostMs: {avg(llm_costs)} ms")
    print(f"P95 llmCostMs: {p95(llm_costs)} ms")

    print("\n【RAG 总耗时】")
    print(f"RAG 请求数(原始): {len(rag_total_costs_raw)}")
    print(f"RAG 请求数(过滤后, ≤{RAG_TOTAL_COST_MAX_MS}ms): {len(rag_total_costs)}")
    print(f"过滤异常值: {len(rag_total_costs_raw) - len(rag_total_costs)}")
    print(f"平均 ragTotalCostMs: {avg(rag_total_costs)} ms")
    print(f"P95 ragTotalCostMs: {p95(rag_total_costs)} ms")

    print("\n【Embedding 缓存】")
    print(f"embedding hit: {embedding_hit}")
    print(f"embedding miss: {embedding_miss}")
    print(f"embedding hit rate: {percent(embedding_hit, embedding_hit + embedding_miss)}")

    print("\n【Search 缓存】")
    print(f"search hit: {search_hit}")
    print(f"search miss: {search_miss}")
    print(f"search hit rate: {percent(search_hit, search_hit + search_miss)}")

    print("\n【拒答】")
    print(f"rag reject count: {rag_reject}")

    print("\n========== 简历可用描述模板 ==========")
    if search_costs:
        print(f"检索链路平均耗时约 {avg(search_costs)} ms，P95 为 {p95(search_costs)} ms。")

    if embedding_hit + embedding_miss > 0:
        print(f"Embedding 缓存命中率达到 {percent(embedding_hit, embedding_hit + embedding_miss)}。")

    if search_hit + search_miss > 0:
        print(f"检索缓存命中率达到 {percent(search_hit, search_hit + search_miss)}。")

    if rag_total_costs:
        print(f"RAG 问答平均响应耗时约 {avg(rag_total_costs)} ms，P95 为 {p95(rag_total_costs)} ms。")


if __name__ == "__main__":
    main()
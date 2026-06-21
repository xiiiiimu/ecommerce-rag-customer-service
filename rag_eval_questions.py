import requests
import csv

API_URL = "http://localhost:8080/vector/search"
MISS_CSV_PATH = "miss_cases.csv"



def call_chat_api(question, session_id):
    try:
        response = requests.post(
            API_URL,
            data=question.encode("utf-8"),
            headers={"Content-Type": "text/plain; charset=utf-8"},
            timeout=60
        )
        return response.json()
    except Exception as e:
        print(f"请求失败: {e}")
        return []


def contains_keywords(text, keywords):
    keyword_list = keywords.split(";")

    for keyword in keyword_list:
        if keyword.strip() in text:
            return True

    return False


def content_preview(text, length=100):
    if text is None:
        return ""
    normalized = str(text).replace("\r\n", "\n").replace("\r", "\n").replace("\n", " ")
    return normalized[:length]


def print_miss_case(question, expected_file, expected_keywords, chunks):
    print("\n========== MISS ==========")
    print("Question:", question)
    print("Expected File:", expected_file)
    print("Expected Keywords:", expected_keywords)
    print("Retrieved Chunks:", len(chunks))

    if not chunks:
        print("  (no chunks returned)")
        return

    for index, chunk in enumerate(chunks, start=1):
        file_name = chunk.get("fileName", "")
        content = chunk.get("content", "")
        preview = content_preview(content)
        print(f"  Chunk[{index}] fileName: {file_name}")
        print(f"  Chunk[{index}] content(100): {preview}")


def save_miss_cases(miss_rows):
    fieldnames = [
        "question",
        "expected_file",
        "expected_keywords",
        "chunk_index",
        "chunk_file_name",
        "chunk_content_preview",
    ]

    with open(MISS_CSV_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(miss_rows)

    print(f"\nMISS cases saved to {MISS_CSV_PATH} ({len(miss_rows)} rows)")


def evaluate(csv_path):
    total = 0
    hit = 0
    total_precision = 0
    miss_rows = []

    with open(csv_path, newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)

        for row in reader:
            question = row["question"]
            expected_file = row["expected_file"]
            expected_keywords = row["expected_keywords"]

            session_id = f"eval_{total + 1}"
            result = call_chat_api(question, session_id)

            if isinstance(result, list):
                chunks = result
            else:
                chunks = result.get("chunks", [])
            success = False
            relevant = 0

            for chunk in chunks:
                file_name = chunk.get("fileName", "")
                content = chunk.get("content", "")

                is_relevant = (
                    expected_file in file_name
                    or contains_keywords(content, expected_keywords)
                )

                if is_relevant:
                    relevant += 1
                    success = True

            precision = relevant / len(chunks) if chunks else 0

            total_precision += precision
            total += 1

            if success:
                hit += 1
                status = "✅ HIT"
            else:
                status = "❌ MISS"
                print_miss_case(question, expected_file, expected_keywords, chunks)

                if not chunks:
                    miss_rows.append({
                        "question": question,
                        "expected_file": expected_file,
                        "expected_keywords": expected_keywords,
                        "chunk_index": "",
                        "chunk_file_name": "",
                        "chunk_content_preview": "",
                    })
                else:
                    for index, chunk in enumerate(chunks, start=1):
                        miss_rows.append({
                            "question": question,
                            "expected_file": expected_file,
                            "expected_keywords": expected_keywords,
                            "chunk_index": index,
                            "chunk_file_name": chunk.get("fileName", ""),
                            "chunk_content_preview": content_preview(chunk.get("content", "")),
                        })

            print(f"[{total}] {status}")
            print("SessionId:", session_id)
            print("Question:", question)
            print("Expected File:", expected_file)
            print("Expected Keywords:", expected_keywords)
            print("Retrieved Chunks:", len(chunks))
            print(f"Precision@TopK: {precision * 100:.2f}%")
            print("-" * 60)

    print("\n========== 最终 Recall 结果 ==========")
    print(f"Total: {total}")
    print(f"Hit: {hit}")
    print(f"Miss: {total - hit}")
    if total > 0:
        print(f"Recall@TopK: {hit / total * 100:.2f}%")
        print(f"Average Precision@TopK: {total_precision / total * 100:.2f}%")
    if miss_rows:
        save_miss_cases(miss_rows)
    else:
        print("\nNo MISS cases, miss_cases.csv not written.")


if __name__ == "__main__":
    evaluate("E:/new_knowledge_system/knowledge_system/rag_eval_questions.csv")

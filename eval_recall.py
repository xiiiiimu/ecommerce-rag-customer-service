import requests
import csv

API_URL = "http://localhost:8080/chat/ask"

def call_chat_api(question):
    try:
        response = requests.post(
            API_URL,
            json={
                "sessionId": "eval001",
                "question": question
            },
            timeout=60
        )
        return response.json()
    except Exception as e:
        print(f"请求失败: {e}")
        return {}

def evaluate(csv_path):
    total = 0

    with open(csv_path, newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)

        for row in reader:
            question = row["question"]

            result = call_chat_api(question)
            answer = result.get("answer", "")
            chunks = result.get("chunks", [])

            total += 1
            print(f"[{total}] {question}")
            print("Answer:", answer)
            print("Chunks:", len(chunks))
            print("-" * 40)

    print("\n===== 最终结果 =====")
    print(f"Total RAG Requests: {total}")

if __name__ == "__main__":
    evaluate("rag_eval_questions.csv")
# Embedding Migration Runbook

**目标：** 将向量存储从 OpenAI `text-embedding-3-small`（1536 维）迁移到 Ollama `nomic-embed-text`（768 维）。

**范围：**

| 保留 | 清空/重建 |
|------|-----------|
| `uploads/` 原始 `.txt` 文档 | `document_chunk_vector` 全部向量行 |
| `customer_order` 订单数据 | Redis embedding / search / answer 缓存 |
| 应用配置（已指向 Ollama） | Elasticsearch `knowledge_chunks` 索引 |

**预计停机：** 15–60 分钟（取决于文档数量与 Ollama 速度）

---

## 0. 前置检查

确认 `application.properties` 已配置 Ollama embedding（当前项目默认值）：

```properties
spring.ai.model.embedding=ollama
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.options.model=nomic-embed-text
```

本地 Ollama 模型（**先拉取，勿在迁移中途执行**）：

```bash
ollama pull nomic-embed-text
curl http://localhost:11434/api/embeddings -d "{\"model\":\"nomic-embed-text\",\"prompt\":\"dimension check\"}"
# 期望：响应 embedding 数组长度为 768
```

数据库连接（默认）：

```
Host: 127.0.0.1:5433
DB:   knowledge_db
User: postgres
```

---

## 1. 停止应用

```bash
# 停止 Spring Boot（Ctrl+C 或关闭运行中的进程）
# Windows 示例：查找占用 8080 的进程并结束
netstat -ano | findstr :8080
```

**迁移期间不要启动应用**，避免写入旧维度向量或触发并发 upload。

---

## 2. 备份（强烈建议）

```bash
# PostgreSQL 逻辑备份（含表结构）
pg_dump -h 127.0.0.1 -p 5433 -U postgres -d knowledge_db \
  -t document_chunk_vector -F c -f document_chunk_vector_1536_backup.dump

# 原始文档已在磁盘，额外备份 uploads 目录
xcopy /E /I uploads uploads_backup_20260612
```

---

## 3. 清空向量数据（PostgreSQL）

> **不删除** `uploads/` 目录。只清空 PG 中的 chunk + embedding 行。

```sql
-- 连接：psql -h 127.0.0.1 -p 5433 -U postgres -d knowledge_db

BEGIN;

-- 3.1 迁移前快照（可选）
SELECT status, COUNT(*) AS chunks
FROM document_chunk_vector
GROUP BY status;

SELECT vector_dims(embedding) AS dim, COUNT(*) AS cnt
FROM document_chunk_vector
WHERE embedding IS NOT NULL
GROUP BY vector_dims(embedding);

-- 3.2 清空向量表（保留表结构）
TRUNCATE TABLE document_chunk_vector RESTART IDENTITY;

-- 3.3 将 embedding 列从 1536 维改为 768 维
-- 若当前列类型为 vector（无固定维度），可跳过 ALTER，TRUNCATE 后直接写入 768 维即可
ALTER TABLE document_chunk_vector
  ALTER COLUMN embedding TYPE vector(768);

-- 3.4 确认表已空
SELECT COUNT(*) AS remaining_chunks FROM document_chunk_vector;

COMMIT;
```

**若 `ALTER COLUMN` 报错**（例如列上存在 ivfflat/hnsw 索引）：

```sql
-- 查看索引
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'document_chunk_vector';

-- 示例：先删向量索引，改维度，重建索引（索引参数按数据量调整）
DROP INDEX IF EXISTS idx_document_chunk_vector_embedding;
ALTER TABLE document_chunk_vector ALTER COLUMN embedding TYPE vector(768);
CREATE INDEX idx_document_chunk_vector_embedding
  ON document_chunk_vector USING hnsw (embedding vector_cosine_ops);
```

---

## 4. 清空检索缓存

### 4.1 Redis

```bash
redis-cli -h 127.0.0.1 -p 6379

# 删除 RAG / embedding 相关 key（保留其他业务 key 时请用 SCAN 而非 FLUSHDB）
KEYS rag:embed:*
KEYS rag:search:*
KEYS rag:answer:*
KEYS embedding:*
KEYS rag:kb:marker
KEYS rag:hot:counter:*

# 批量删除示例（PowerShell 可改用 redis-cli --scan）
DEL rag:kb:marker
# 对上述 pattern 逐类 DEL，或运维脚本批量删除
```

### 4.2 Elasticsearch BM25 索引

```bash
# 删除旧索引（应用启动时会自动重建 knowledge_chunks）
curl -X DELETE "http://localhost:9200/knowledge_chunks"

# 验证
curl "http://localhost:9200/_cat/indices/knowledge_chunks?v"
# 期望：404 或索引不存在
```

---

## 5. 启动依赖服务

按顺序启动（与 `application.properties` 一致）：

```bash
# 1) Ollama
ollama serve
# 另开终端确认模型
ollama list | findstr nomic-embed-text

# 2) PostgreSQL（pgvector）— 端口 5433
# 3) Redis — 6379
# 4) Elasticsearch — 9200
# 5) Kafka — 9092（若使用异步 upload；同步 upload 可暂不启）
```

---

## 6. 启动应用

```bash
cd e:\new_knowledge_system\knowledge_system

# 编译
mvn clean compile -DskipTests

# 启动（开发模式）
mvn spring-boot:run
```

**启动日志应包含：**

```
embeddingProvider=Ollama
embeddingModel=nomic-embed-text
Ollama connectivity OK
```

---

## 7. 重新入库 Knowledge 文档（Policy / Rules）

原始文档位于 `uploads/`（**保留，不删除**）。以下示例使用 `2026-05-18` 批次中的规则文件（按实际文件名调整）。

### 7.1 单文件上传（curl）

```bash
BASE=http://localhost:8080/vector/upload

curl -X POST "%BASE%" \
  -F "file=@uploads/2026-05-18/5655fad9-09ee-4c32-9f63-acb2486196a1-coupon_rules.txt" \
  -F "docType=POLICY" \
  -F "status=ACTIVE"

curl -X POST "%BASE%" \
  -F "file=@uploads/2026-05-18/e134100e-3404-4cb4-ab07-44663dde24a7-logistics_rules.txt" \
  -F "docType=POLICY" \
  -F "status=ACTIVE"

curl -X POST "%BASE%" \
  -F "file=@uploads/2026-05-18/212e85fd-3e64-42d1-ab26-f0db9959ecb0-refund_rules.txt" \
  -F "docType=POLICY" \
  -F "status=ACTIVE"

curl -X POST "%BASE%" \
  -F "file=@uploads/2026-05-18/126b59ba-b73a-4733-bdff-f038f579ea7f-return_rules.txt" \
  -F "docType=POLICY" \
  -F "status=ACTIVE"

curl -X POST "%BASE%" \
  -F "file=@uploads/2026-05-18/4a8af43e-9490-4d43-bc7c-a99722191584-shipping_rules.txt" \
  -F "docType=POLICY" \
  -F "status=ACTIVE"

curl -X POST "%BASE%" \
  -F "file=@uploads/2026-05-18/20256b0b-82e2-40d9-8f07-64989b06168f-double11_rules.txt" \
  -F "docType=POLICY" \
  -F "status=ACTIVE"
```

### 7.2 批量上传（PowerShell 示例）

```powershell
$base = "http://localhost:8080/vector/upload-multi"
$files = Get-ChildItem "uploads/2026-05-18/*.txt"
$form = @{ docType = "POLICY"; status = "ACTIVE" }
# 使用 curl.exe 或 Invoke-RestMethod 构造 multipart，逐批上传
```

每次 upload 会：**Ollama embed → INSERT PG(768) → INDEX ES → 刷新 kb marker**。

---

## 8. 重新执行 FAQ Seed

FAQ 格式要求：`问：…` / `答：…`（见 `VectorService.splitFaq`）。

### 方式 A — Benchmark 测试自动 seed（100 条 FAQ）

```bash
# 会写入 benchmark_faq_100.txt（100 chunks），并尝试 policy/orders
# 若仅需 FAQ，可在跑完后 SQL 删除 benchmark_policy_* 行
mvn test -Dtest=EcommerceCustomerServiceBenchmarkTest#groupC_existingData
```

### 方式 B — API 上传 FAQ 文件

准备 `faq_seed.txt`（示例）：

```text
问：优惠券通常有什么限制？
答：使用门槛、适用商品范围、有效期等限制以活动页为准。

问：退款申请多久审核？
答：一般 1 至 3 个工作日完成审核。
```

```bash
curl -X POST "http://localhost:8080/vector/upload" \
  -F "file=@faq_seed.txt" \
  -F "docType=FAQ" \
  -F "status=ACTIVE"
```

---

## 9. 重新执行 Knowledge Seed（Benchmark 扩展集，可选）

若需要 Benchmark Group C 的 100+ 政策文档：

```bash
mvn test -Dtest=EcommerceCustomerServiceBenchmarkTest#groupC_existingData
```

或手动循环调用 `POST /vector/upload`，文件名模式 `benchmark_policy_XXX.txt`，`docType=POLICY`。

---

## 10. 验证向量维度一致

### 10.1 PostgreSQL — 维度分布

```sql
-- 所有 embedding 必须均为 768，且无 NULL
SELECT
  vector_dims(embedding) AS dimension,
  COUNT(*)               AS chunk_count
FROM document_chunk_vector
WHERE embedding IS NOT NULL
GROUP BY vector_dims(embedding)
ORDER BY dimension;

-- 期望结果：
-- dimension | chunk_count
-- ----------+------------
--       768 |         N   （N > 0，无 1536 行）

-- 不应存在混合维度
SELECT COUNT(*) AS invalid_rows
FROM document_chunk_vector
WHERE embedding IS NOT NULL
  AND vector_dims(embedding) <> 768;
-- 期望：0
```

### 10.2 PostgreSQL — 列类型

```sql
SELECT column_name, udt_name, character_maximum_length
FROM information_schema.columns
WHERE table_name = 'document_chunk_vector'
  AND column_name = 'embedding';
-- pgvector 显示为 USER-DEFINED；进一步：
SELECT format_type(a.atttypid, a.atttypmod) AS embedding_type
FROM pg_attribute a
JOIN pg_class c ON a.attrelid = c.oid
WHERE c.relname = 'document_chunk_vector'
  AND a.attname = 'embedding'
  AND NOT a.attisdropped;
-- 期望包含：vector(768)
```

### 10.3 PostgreSQL — 相似度查询冒烟

```sql
-- 取任意一条 768 维向量做 self-search（不应报 dimension error）
WITH sample AS (
  SELECT embedding
  FROM document_chunk_vector
  WHERE embedding IS NOT NULL
  LIMIT 1
)
SELECT id, file_name, chunk_index,
       1 - (embedding <=> (SELECT embedding FROM sample)) AS score
FROM document_chunk_vector
WHERE status = 'ACTIVE'
ORDER BY embedding <=> (SELECT embedding FROM sample)
LIMIT 5;
-- 期望：返回 5 行，无 ERROR: different vector dimensions
```

### 10.4 应用层 — 检索 API

```bash
curl -X POST "http://localhost:8080/vector/search" \
  -H "Content-Type: text/plain" \
  -d "优惠券通常有什么限制"
# 期望：JSON 数组非空，含 file_name / score，日志无 dimension 错误

curl -X POST "http://localhost:8080/chat/ask" \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"migration-smoke-1\",\"question\":\"退款申请多久审核\"}"
# 期望：返回答案，非 empty_retrieval
```

### 10.5 Ollama — 模型输出维度

```bash
curl http://localhost:11434/api/embeddings \
  -d "{\"model\":\"nomic-embed-text\",\"prompt\":\"test\"}" \
  | jq ".embedding | length"
# 期望：768
```

### 10.6 Benchmark 回归（可选）

```bash
mvn test -Dtest=EcommerceCustomerServiceBenchmarkTest
# 检查 target/benchmark/benchmark_report.md
# Group C retrievalRecall 应 > 0（在知识文档已正确入库前提下）
```

---

## 11. 回滚方案

若迁移失败，从备份恢复：

```bash
# 恢复 PG 表
pg_restore -h 127.0.0.1 -p 5433 -U postgres -d knowledge_db \
  --clean --if-exists -t document_chunk_vector document_chunk_vector_1536_backup.dump

# application.properties 改回 OpenAI embedding（若需回退模型）
# spring.ai.model.embedding=openai
# spring.ai.openai.embedding.options.model=text-embedding-3-small

# 清空 Redis + 重建 ES 索引后重启应用
```

---

## 12. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| `expected 1536 dimensions, not 768` | 列仍为 vector(1536) 或表内混有旧行 | TRUNCATE + `ALTER ... vector(768)` |
| `different vector dimensions 1536 and 768` | 查询向量 768，表内仍有 1536 行 | 清空表后全量 re-upload |
| INSERT 成功但 search 为空 | ES 未索引 / Redis 旧缓存 | 删 ES 索引、清 Redis、重启 |
| FAQ 只有 1 个 chunk | 文件未用 `问：`/`答：` 格式 | 按 splitFaq 格式重写 |
| upload 很慢 | Ollama CPU 推理 | 批量离线 upload，或增大 Ollama 资源 |

---

## 13. 迁移检查清单

- [ ] 已停止应用
- [ ] 已备份 `document_chunk_vector` 与 `uploads/`
- [ ] 已 `TRUNCATE document_chunk_vector`
- [ ] 已 `ALTER COLUMN embedding TYPE vector(768)`
- [ ] 已清 Redis `rag:*` / `embedding:*`
- [ ] 已删 ES `knowledge_chunks`
- [ ] Ollama `nomic-embed-text` 可用（768 维）
- [ ] 已从 `uploads/` 重新 upload 全部 Knowledge 文档
- [ ] 已执行 FAQ seed
- [ ] 验证 SQL：`vector_dims` 全部为 768
- [ ] 验证 API：`/vector/search` 与 `/chat/ask` 正常
- [ ] （可选）Benchmark recall > 0

---

*文档版本：2026-06-12 · 项目：knowledge_system · 仅 Runbook，不包含自动执行脚本。*

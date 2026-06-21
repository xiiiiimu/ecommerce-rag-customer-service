# ecommerce-rag-customer-service

电商智能客服系统：Spring Boot + RAG（BM25 / PGVector 混合检索）+ Reliability Orchestrator。

详细设计见 [技术文档.md](技术文档.md)。

## 技术栈

- Java 17, Spring Boot 3.2
- LLM: DeepSeek（OpenAI 兼容 API）
- Embedding: Ollama `nomic-embed-text`（768 维）
- PostgreSQL + PGVector, Elasticsearch, Redis, Kafka
- MyBatis, Caffeine 本地缓存

## 依赖服务

| 服务 | 默认地址 |
|------|----------|
| PostgreSQL | `127.0.0.1:5433` |
| Redis | `127.0.0.1:6379` |
| Elasticsearch | `http://localhost:9200` |
| Kafka | `127.0.0.1:9092` |
| Ollama | `http://localhost:11434` |

## 本地配置

1. 复制配置模板：

```bash
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
```

2. 在 `application-local.properties` 或环境变量中设置：

- `DEEPSEEK_API_KEY` — DeepSeek API 密钥（必填）
- `DB_PASSWORD` — PostgreSQL 密码（必填）

PowerShell 示例：

```powershell
$env:DEEPSEEK_API_KEY = "your-key"
$env:DB_PASSWORD = "your-password"
```

## 启动

```bash
mvn spring-boot:run
```

主接口：

- `POST /chat/ask` — 智能客服问答（推荐）
- `POST /vector/upload` — 知识库文档上传

## Benchmark

```powershell
mvn test "-Dtest=RetrievalBenchmarkTest,CacheBenchmarkTest,E2EBenchmarkTest,ReliabilityBenchmarkTest,ZBenchmarkDashboardTest"
```

报告输出：`target/benchmark/`

## 项目结构

```
src/main/java/.../orchestration/   # RagOrchestrator 可靠性编排
src/main/java/.../service/         # VectorService, 检索与缓存
src/test/java/.../benchmark/       # 评测框架（Retrieval / Cache / E2E / Reliability）
src/test/resources/benchmark/      # Golden 数据集与评测问题
docs/                              # 运维文档（如 embedding 迁移 runbook）
```

## 文档

| 文档 | 说明 |
|------|------|
| [技术文档.md](技术文档.md) | 架构、Reliability 编排、API、缓存 |
| [docs/embedding-migration-runbook.md](docs/embedding-migration-runbook.md) | 1536→768 维迁移步骤 |

## 推送到 GitHub

仓库地址：[github.com/xiiiiimu/ecommerce-rag-customer-service](https://github.com/xiiiiimu/ecommerce-rag-customer-service)

```powershell
git remote add origin https://github.com/xiiiiimu/ecommerce-rag-customer-service.git
git push -u origin main
```

**注意**：切勿将 API Key、数据库密码提交到仓库。`application-local.properties` 已在 `.gitignore` 中排除。

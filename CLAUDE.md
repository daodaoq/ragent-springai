# CLAUDE.md — ragent-SpringAI

人工智能实验室在线问答系统：Spring AI + DeepSeek + Qdrant 的 RAG 知识库 + 师生问答 + Agent。多模块 Maven 后端（`common ← ai ← web`）+ React 19 前端。

## 构建 / 运行

- **JDK 必须显式指向 21**：`JAVA_HOME="D:/jdk/jdk21" mvn ...`（系统 JAVA_HOME 仍是 17，不指定会编译失败）。
- 后端编译：`cd backend && JAVA_HOME="D:/jdk/jdk21" mvn -pl ragent-web -am compile`
- 测试：`JAVA_HOME="D:/jdk/jdk21" mvn -pl ragent-ai -am test`（纯逻辑单测，已写：熔断器/规范化/检索/切分）
- 前端：`cd frontend && npm install && npm run dev`；类型检查 `npx tsc -b`；生产构建 `npx vite build`
- 中间件：`docker compose up -d`（MySQL/Redis/Qdrant/MinIO/ELK）。

## 关键约定 / 坑

- **API Key 在 gitignored 的 `application-local.yml`**（DeepSeek + DashScope），`application.yml` 里是空 fallback。密钥硬编码进 fallback 是遗留风险，勿再扩散。
- **雪花 Long ID 必须字符串序列化**：`JacksonConfig` 已把 boxed Long 全局转字符串；新增接口返回雪花 ID 用 boxed Long，统计计数用 primitive long。前端严禁 `Number(id)`。
- **Qdrant 走 gRPC 端口 6334**（不是 REST 6333）；payload 不支持 Long，雪花 ID 转 String；`initialize-schema: true`。
- **检索两端都要过滤文档状态**：稠密向量通道靠 `RetrievalServiceImpl.filterActiveDocuments`（按 documentId 反查 `kb_document`：READY + deleted=0 + source=UPLOAD）；关键词通道 SQL 已带 `d.status='READY' AND d.deleted=0`。改检索必须保持两路一致。
- **评测样例文档标 `source=EVAL`**：生产检索与知识库列表排除；评测检索用 `ragService.retrieve(q, k, processed, true)`。
- **会话记忆键带用户作用域**：`RagentContext.userScope()`（登录→`u{id}`，匿名→`anon`），改记忆逻辑别丢掉前缀。
- **SSE 事件协议**：`/ai/stream` 先 `mode` → 引擎事件（RAG：`rewritten` → `sources` → `content`；Agent：`tool-call` → `content`；Chat：`content`）；旧端点 `/ai/chat|rag|agent/stream` 是限流兼容壳。
- **限流**：`RedisRateLimiter` 的 release Lua 是状态感知的（仅 admitted 才 DECR inFlight）；新加限流路径只对"曾 admitted"的请求调 release。
- **异步线程一律走 `RagentThreadPools.newExecutor`**（TTL+MDC 透传），否则 traceId/userId 丢失。
- **MyBatis-Plus 手写 `@Select` 必须手动 `deleted=0`**（`question_tag` 无 deleted 列不过滤）；record 参数映射依赖 `-parameters` 编译器参数。

## 迁移脚本（一次性，勿重复）

`backend/sql/` 下：`schema.sql`（新装）、`p4_retrieval.sql`（FULLTEXT 索引，缺失关键词通道降级）、`p8_trace_id.sql` / `p8_eval.sql` / `p8_source.sql`（P8 增量列/表）。启动时 `FulltextIndexCheck` 自检 FULLTEXT 索引并打 WARN。

## 文档

- `docs/技术选型.md`（P0-P5 路线图）、`docs/P4-检索优化.md`、`docs/P5-Agent与收尾.md`、`docs/P8-审查报告.md`（审查+修复清单，P0/P1 已完成项打勾）、`docs/P9-架构文档.md`（P6/P7/P8 沉淀）、`docs/P9-部署与运维.md`。
- 新增架构改动请同步更新 `docs/P9-架构文档.md` 与审查报告勾选状态。

## 当前已知待办（P1/P2 剩余）

- P1：5a 异步摄取任务表、5c OCR、7a 检索缓存（已做）→ 剩余 5a/5c。
- P2：多知识库/租户隔离、RAGAS context 指标、密钥默认值清理、中间件鉴权、HA、应用容器化。

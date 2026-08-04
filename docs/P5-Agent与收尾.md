# P5 Agent 工具调用 + 收尾

> 交付：Agent 智能体（Spring AI 手动工具循环，直查题库/统计）+ Redis 多轮会话记忆 + AI 答案赞/踩反馈 + ECharts 数据看板 + KaTeX 公式渲染。路线图收官，达到「可演示/可部署」。

## 一、Agent 工具调用（核心）

**手动有界工具循环**（非自动循环）。原理：
- 工具 callbacks 设置在 `DefaultToolCallingChatOptions` 上，经 `prompt().options(options)` 传入。
  不能依赖 `.tools(...)`——`DefaultChatClientUtils` 里 `.tools()` 仅在 options 是 `ToolCallingChatOptions` 时才合并（已用 jar 核实）。
- `internalToolExecutionEnabled(false)` 让模型返回原始 tool-call 消息而不自动执行，由服务逐个执行、
  逐条发 SSE `tool-call` 事件，前端展示工具 chips。
- 有界 `MAX_ROUNDS=6`，防止 DeepSeek function-calling 不稳定导致的死循环；单轮耗尽则回退兜底文案。
- 工具循环是同步 `.call()`，整体包 `Flux.defer(...).subscribeOn(Schedulers.boundedElastic())`，
  避免阻塞 Netty 事件循环。

**工具集**（`AgentTools`，全只读，返回 JSON 字符串，复用领域服务）：

| 工具 | 说明 |
|---|---|
| `searchQuestions(keyword, limit)` | 关键词搜题库 |
| `getQuestionDetail(id)` | 问题详情（用只读 `detailReadOnly`，**不自增浏览数**） |
| `listTags()` | 列出全部标签 |
| `countQuestionsByTag(tag)` | 某标签下问题数 |
| `getQuestionStats()` | 题库总览统计 |
| `getCurrentUserInfo()` | 当前登录用户（未登录返回 null，`StpUtil.isLogin()` 防御） |

接口：`POST /api/ai/agent/stream` → SSE（`tool-call` 事件 + 最终 `content` 事件）。Agent 也写多轮记忆。

## 二、多轮会话记忆（Redis）

- `ChatMemoryService`（web）：key `chat:memory:{conversationId}`（空 → `default`），
  StringRedisTemplate + ObjectMapper 存 JSON 数组（最近 12 条 `{role,content}`，TTL 7 天，每次写入刷新）。
- `ChatService` 从 `ragent-ai` 移到 `ragent-web`（同 P4 RagService 模式），`stream(message, conversationId)`
  组装 `[System, 历史..., User]`，`doOnComplete` 写回记忆。
- 记忆用于 普通对话 + Agent 模式；RAG 保持无状态。
- 接口：`POST /api/ai/memory/clear` 清空某会话。

## 三、AI 答案反馈（赞/踩）

- 表 `ai_feedback`（append-only，无逻辑删除）：`id/user_id(可空)/conversation_id/question/answer/rating/created_at`。
  迁移 `backend/sql/p5_ai_feedback.sql`（已并入 `schema.sql`）。
- `POST /api/ai/feedback`（公开，登录则记 user_id；rating=1 赞 / -1 踩）
- `GET /api/ai/feedback/stats`（登录）：总数/赞/踩/好评率，供看板。

## 四、数据统计看板（ECharts）

- `StatsService` + `StatsController`（`/api/stats/*`，全部 `@SaCheckLogin`）：
  - `/overview`：问题/回答/用户/标签数（`selectCount(new LambdaQueryWrapper<>())` 自动带逻辑删除）
  - `/question-trend?days=14`：每日提问数（QuestionMapper `@Select` DATE 分组）
  - `/tag-distribution`：标签分布（JOIN question_tag/question/tag）
  - `/top-askers?limit=5`：Top 提问者
- 手写 `@Select` 必须手动 `deleted=0`（`question_tag` 无 deleted 列不过滤）；
  record + 下划线转驼峰 + `-parameters` 映射（DocumentChunkMapper.KeywordRow 同款）。
- 前端 `DashboardPage` + `api/stats.ts`：5 张统计卡 + 3 张图（趋势折线/标签柱状/Top提问者横向柱状）。
  `useChart` hook 在 cleanup 里 `chart.dispose()`，兼容 React 19 StrictMode 双挂载。

## 五、KaTeX 数学公式

- `Markdown.tsx` 加 `remark-math` + `rehype-katex` + `katex.min.css`：`$行内$` / `$$块级$$` 渲染。

## 六、前端结构

- `api/ai.ts`：SSE 解析器修复——一个事件块内可有多个 `data:` 行（Agent 非流式 content 多行内容），
  按 SSE 规范以换行连接；新增 `tool-call` 事件分支、`streamAgent`、`streamChat(message, conversationId)`、`clearMemory`。
- `ChatPage`：三模式（知识库问答/普通对话/Agent 智能体），每模式独立 conversationId（localStorage）；
  助手消息下工具 chips（🔧）+ 赞/踩按钮 + 「清空对话」。
- 路由/导航：`/dashboard` + 「数据看板」（仅登录可见）。

## 验证结果（2026-08-04）

- Agent：`POST /api/ai/agent/stream`「查一下题库里深度学习相关的提问」→ `tool-call` 事件（searchQuestions →
  getQuestionDetail）→ 结构化最终答案（含问题详情/最佳答案/小结），单轮收敛。
- 记忆：同一 conversationId 第二轮「我刚才说了什么？」→ 正确引用「强化学习」；`memory/clear` 后不再记得。
- 反馈：赞/踩入库，`stats` 返回 `total=2, up=1, down=1, upRate=50`。
- 看板：overview（2 问题/2 回答/4 用户/5 标签）、trend、tag-distribution、top-askers 全部返回正确。
- 回归 `POST /api/eval/run`：Recall@5/MRR/NDCG=1.0，忠实度 5.0，相关度 4.7（LLM 裁判随机波动，基线 5.0），引用率 100%。
- 前端 `tsc -b` 通过、Vite dev 全模块编译通过、生产构建通过。

## 参考

- Spring AI 1.0.0 工具调用：`@Tool` / `MethodToolCallbackProvider` / `ToolCallbacks.from` /
  `DefaultToolCallingChatOptions.internalToolExecutionEnabled(false)` / `AssistantMessage.ToolCall` /
  `ToolResponseMessage.ToolResponse`（API 均经本地 .m2 jar javap 核实）。
- 路线图见 `docs/技术选型.md`，P5 全部 ✅。

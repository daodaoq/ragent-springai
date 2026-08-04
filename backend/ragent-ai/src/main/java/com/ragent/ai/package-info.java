/**
 * Spring AI 能力模块（LLM / RAG / Agent 基础设施）。
 * 承载：DeepSeek ChatClient 配置、普通对话与多轮记忆、混合检索（Qdrant 向量 + MySQL FULLTEXT + 重排）、
 * RAG 问答、知识库文档处理（上传→分片→向量化）、分片器、重排客户端、AI 调用容错与检索评测。
 * 依赖方向：ragent-common ← ragent-ai ← ragent-web（web 承担领域服务与 Agent 组装层）。
 */
package com.ragent.ai;

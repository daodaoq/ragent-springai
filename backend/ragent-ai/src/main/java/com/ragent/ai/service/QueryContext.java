package com.ragent.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询处理管线上下文：承载原始问题、历史、各阶段产物，以及共享的一次性懒加载结构化提取结果。
 * LLM 密集阶段（intent/rewrite/multiQuery/hyde/entity）都从 {@link #structured()} 读同一份结果，
 * 保证「阶段可插拔」的同时仍然只发一次 LLM 调用。
 */
public class QueryContext {

    private final String rawQuestion;
    private final List<ChatMemoryService.ChatMessage> history;

    private String normalized = "";
    private String intent = "RAG";
    private String rewrittenQuery;
    private List<String> variants = List.of();
    private String hyde;
    private String filename;
    private Integer page;
    private List<ChatMemoryService.ChatMessage> contextHistory;
    private final List<QueryPipeline.StageRun> runs = new ArrayList<>();

    private StructuredExtractor extractor;
    private List<String> neededFields;
    private StructuredExtractor.Result structured;

    public QueryContext(String rawQuestion, List<ChatMemoryService.ChatMessage> history) {
        this.rawQuestion = rawQuestion == null ? "" : rawQuestion;
        this.history = history == null ? List.of() : history;
    }

    /** 由 QueryPipeline 在执行前配置：提取器 + 启用阶段所需字段并集 */
    public void configure(StructuredExtractor extractor, List<String> neededFields) {
        this.extractor = extractor;
        this.neededFields = neededFields;
    }

    /** 懒加载共享的结构化提取结果（首访触发一次 LLM 调用；提取器异常/未配置时兜底为空） */
    public StructuredExtractor.Result structured() {
        if (structured == null) {
            structured = (extractor == null || neededFields == null || neededFields.isEmpty())
                    ? StructuredExtractor.Result.empty()
                    : extractor.extract(rawQuestion, contextHistory(), neededFields);
        }
        return structured;
    }

    public void addRun(QueryPipeline.StageRun run) {
        runs.add(run);
    }

    // ==================== getters / setters ====================

    public String rawQuestion() {
        return rawQuestion;
    }

    public List<ChatMemoryService.ChatMessage> history() {
        return history;
    }

    /** context 阶段裁剪后的历史；未裁剪（该阶段停用）时退化为全部历史 */
    public List<ChatMemoryService.ChatMessage> contextHistory() {
        return contextHistory != null ? contextHistory : history;
    }

    public void setContextHistory(List<ChatMemoryService.ChatMessage> v) {
        this.contextHistory = v;
    }

    public String normalized() {
        return normalized;
    }

    public void setNormalized(String v) {
        this.normalized = v == null ? "" : v;
    }

    public String intent() {
        return intent;
    }

    public void setIntent(String v) {
        this.intent = v == null || v.isBlank() ? "RAG" : v;
    }

    public String rewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String v) {
        this.rewrittenQuery = v;
    }

    public List<String> variants() {
        return variants;
    }

    public void setVariants(List<String> v) {
        this.variants = v == null ? List.of() : v;
    }

    public String hyde() {
        return hyde;
    }

    public void setHyde(String v) {
        this.hyde = v;
    }

    public String filename() {
        return filename;
    }

    public void setFilename(String v) {
        this.filename = v;
    }

    public Integer page() {
        return page;
    }

    public void setPage(Integer v) {
        this.page = v;
    }

    public List<QueryPipeline.StageRun> runs() {
        return runs;
    }
}

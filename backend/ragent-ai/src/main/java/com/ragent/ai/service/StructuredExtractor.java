package com.ragent.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化查询提取器：把「意图 + 改写 + 变体 + HyDE + 实体」合并为一次 LLM 调用。
 * 只请求当前启用阶段所需字段（动态生成 JSON schema 指令），未启用的字段不进 prompt，省 token/延迟。
 * 任何异常/未配置 ChatClient 都兜底返回空结构（intent=RAG），由各阶段各自降级，绝不打断问答。
 */
@Slf4j
@Component
public class StructuredExtractor {

    private static final String SYSTEM_PROMPT = """
            你是检索查询优化器。根据用户的问题（必要时结合历史对话）输出 JSON。
            只输出一个 JSON 对象，不要输出任何其他文字，不要用代码围栏。""";

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final RetrievalProperties props;

    public StructuredExtractor(ObjectProvider<ChatClient> chatClientProvider, ObjectMapper objectMapper,
                               KbDocumentMapper kbDocumentMapper, RetrievalProperties props) {
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
        this.kbDocumentMapper = kbDocumentMapper;
        this.props = props;
    }

    /** 结构化提取结果（所有字段可缺省；intent 缺省视为 RAG） */
    public record Result(String intent, String rewrittenQuery, List<String> variants,
                         String hyde, String filename, Integer page) {
        public static Result empty() {
            return new Result("RAG", null, List.of(), null, null, null);
        }
    }

    public Result extract(String rawQuestion, List<ChatMemoryService.ChatMessage> history, List<String> neededFields) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null || neededFields == null || neededFields.isEmpty()) {
            return Result.empty();
        }
        try {
            String prompt = buildPrompt(rawQuestion, history, neededFields);
            String raw = AiRetry.callWithRetry(() -> chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call().content());
            return parse(raw, neededFields);
        } catch (Exception e) {
            log.warn("查询结构化提取失败，回退原句: {}", e.getMessage());
            return Result.empty();
        }
    }

    // ==================== prompt ====================

    private String buildPrompt(String question, List<ChatMemoryService.ChatMessage> history, List<String> needed) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前问题：").append(question).append('\n');
        if (history != null && !history.isEmpty()) {
            sb.append("历史对话（最近几轮）：\n");
            for (ChatMemoryService.ChatMessage m : history) {
                sb.append(m.role()).append(": ").append(m.content()).append('\n');
            }
        }
        if (needed.contains("filename")) {
            List<String> filenames = listReadyFilenames();
            if (!filenames.isEmpty()) {
                sb.append("知识库现有文档名（filename 字段必须从中精确匹配）：\n");
                for (String f : filenames) {
                    sb.append("- ").append(f).append('\n');
                }
            }
        }
        sb.append("\n请输出 JSON 对象，各字段说明：\n");
        if (needed.contains("intent")) {
            sb.append("\"intent\": 枚举之一 RAG(知识库相关问题)/AGENT(需要查题库、统计、标签等真实数据的工具类问题)/CHAT(闲聊寒暄)/OTHER(其他)，只取一个\n");
        }
        if (needed.contains("rewrittenQuery")) {
            sb.append("\"rewrittenQuery\": 独立完整、术语展开、适合向量与关键词检索的长查询字符串；若依赖历史，请把指代补全\n");
        }
        if (needed.contains("variants")) {
            sb.append("\"variants\": 长度为 ").append(props.getMultiQueryCount())
                    .append(" 的字符串数组，每个元素是当前问题一种不同问法的改写变体\n");
        }
        if (needed.contains("hyde")) {
            sb.append("\"hyde\": 假设知识库文档中会如何描述该主题，写 2~3 句陈述句，不要用提问口吻\n");
        }
        if (needed.contains("filename")) {
            sb.append("\"filename\": 若问题明确提到某个知识库文档名（形如 xxx.md / xxx.pdf），必须把该名称填入并从上表精确匹配；未明确提到则为 null\n");
        }
        if (needed.contains("page")) {
            sb.append("\"page\": 若问题明确提到页码（如“第 3 页”“第12页”），填数字；未提到则为 null\n");
        }
        sb.append("只输出包含上述字段的 JSON 对象，未要求的字段不要输出。");
        return sb.toString();
    }

    private List<String> listReadyFilenames() {
        try {
            List<KbDocument> docs = kbDocumentMapper.selectList(
                    new LambdaQueryWrapper<KbDocument>()
                            .eq(KbDocument::getStatus, "READY")
                            .last("LIMIT 100"));
            return docs.stream()
                    .map(KbDocument::getFilename)
                    .filter(f -> f != null && !f.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("读取知识库文件名失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 解析 ====================

    private Result parse(String raw, List<String> needed) {
        if (raw == null) {
            return Result.empty();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Result.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            String intent = node.path("intent").isTextual() ? node.path("intent").asText() : "RAG";
            String rewritten = node.path("rewrittenQuery").isTextual()
                    ? node.path("rewrittenQuery").asText() : null;
            List<String> variants = new ArrayList<>();
            JsonNode v = node.path("variants");
            if (v.isArray()) {
                for (JsonNode e : v) {
                    if (e.isTextual() && !e.asText().isBlank()) {
                        variants.add(e.asText().trim());
                    }
                }
            }
            String hyde = node.path("hyde").isTextual() ? node.path("hyde").asText() : null;
            String filename = node.path("filename").isTextual() && !node.path("filename").asText().isBlank()
                    ? node.path("filename").asText().trim() : null;
            Integer page = node.path("page").isInt() ? node.path("page").asInt() : null;
            return new Result(intent, rewritten, variants, hyde, filename, page);
        } catch (Exception e) {
            log.warn("结构化输出解析失败，回退原句: {}", raw);
            return Result.empty();
        }
    }
}

package com.ragent.ai.eval.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.entity.EvalResult;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.eval.EvalService;
import com.ragent.ai.mapper.EvalResultMapper;
import com.ragent.ai.service.KnowledgeBaseService;
import com.ragent.ai.service.RagService;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索与回答质量评测实现。
 * 流程：种子知识库 → 逐题检索+生成+LLM裁判打分 → 汇总指标。
 * 触发：POST /api/eval/run，返回可量化的评测报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalServiceImpl implements EvalService {

    private static final String[] SAMPLE_DOCS =
            {"pytorch-setup.md", "deepseek-config.md", "backprop.md", "cnn.md", "lab-safety.md"};

    private static final int TOP_K = 5;

    private final KnowledgeBaseService kbService;
    private final RagService ragService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final EvalResultMapper evalResultMapper;

    @Override
    public EvalReport run(boolean processed) {
        return run(processed, true);
    }

    @Override
    public EvalReport run(boolean processed, boolean withAnswer) {
        Map<String, Long> docIdByFile = seedDocs();
        List<EvalCase> cases = loadCases();
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            results.add(runCase(c, docIdByFile, processed, withAnswer));
        }
        EvalReport report = aggregate(results);
        // P8-6b：持久化本次结果，供历史对比/回归追踪
        persist(report, processed, withAnswer);
        return report;
    }

    // ==================== P8-6b 持久化 ====================

    private void persist(EvalReport report, boolean processed, boolean withAnswer) {
        try {
            EvalResult e = new EvalResult();
            e.setProcessed(processed);
            e.setWithAnswer(withAnswer);
            e.setTotalCases(report.totalCases());
            e.setRecall(report.retrieval().recallAt5());
            e.setPrecision(report.retrieval().precisionAt5());
            e.setMrr(report.retrieval().mrrAt5());
            e.setNdcg(report.retrieval().ndcgAt5());
            e.setAvgFaithfulness(report.answer().avgFaithfulness());
            e.setAvgRelevance(report.answer().avgRelevance());
            e.setCitationRate(report.answer().citationRate());
            e.setDetailJson(objectMapper.writeValueAsString(report));
            evalResultMapper.insert(e);
            log.info("评测结果已持久化: id={}, recall={}, ndcg={}, 忠实度={}",
                    e.getId(), report.retrieval().recallAt5(), report.retrieval().ndcgAt5(),
                    report.answer().avgFaithfulness());
        } catch (Exception e) {
            log.warn("评测结果持久化失败（不影响本次返回）: {}", e.getMessage());
        }
    }

    @Override
    public List<EvalResult> history(int limit) {
        return evalResultMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvalResult>()
                        .select(EvalResult::getId, EvalResult::getProcessed, EvalResult::getWithAnswer,
                                EvalResult::getTotalCases, EvalResult::getRecall, EvalResult::getPrecision,
                                EvalResult::getMrr, EvalResult::getNdcg, EvalResult::getAvgFaithfulness,
                                EvalResult::getAvgRelevance, EvalResult::getCitationRate, EvalResult::getCreatedAt)
                        .orderByDesc(EvalResult::getCreatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    // ==================== 步骤 ====================

    private Map<String, Long> seedDocs() {
        Map<String, Long> map = new HashMap<>();
        for (String filename : SAMPLE_DOCS) {
            try {
                Resource res = resourceLoader.getResource("classpath:eval/docs/" + filename);
                String text = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                KbDocument doc = kbService.uploadTextIfAbsent(filename, text);
                // P8-6c：标记为 EVAL 评测文档——生产检索（includeEval=false）会排除，不再污染真实知识库
                kbService.markSource(doc.getId(), "EVAL");
                map.put(filename, doc.getId());
            } catch (Exception e) {
                log.error("评测种子文档失败: {}", filename, e);
            }
        }
        return map;
    }

    private List<EvalCase> loadCases() {
        try {
            Resource res = resourceLoader.getResource("classpath:eval/eval-set.json");
            byte[] bytes = res.getInputStream().readAllBytes();
            return objectMapper.readValue(bytes, new TypeReference<List<EvalCase>>() {
            });
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "加载评测集失败: " + e.getMessage());
        }
    }

    private CaseResult runCase(EvalCase c, Map<String, Long> docIdByFile, boolean processed, boolean withAnswer) {
        List<Long> relevantDocIds = c.docs().stream()
                .map(docIdByFile::get)
                .filter(Objects::nonNull)
                .toList();

        // 1. 检索（processed 走查询处理管线，gateByIntent=false；includeEval=true 允许召回 EVAL 评测文档）
        List<Document> retrieved = ragService.retrieve(c.question(), TOP_K, processed, true);
        // 2. 文档级相关性：同一期望文档的多个切片只算第一个命中（否则 recall 会因切片数而 >1，指标失真）
        Set<Long> seenRelevant = new HashSet<>();
        List<Boolean> relevant = new ArrayList<>();
        for (Document d : retrieved) {
            Long docId = parseLong(d.getMetadata().get("documentId"));
            relevant.add(relevantDocIds.contains(docId) && seenRelevant.add(docId));
        }

        // 2. 指标
        double recall = recallAtK(relevant, relevantDocIds.size());
        double precision = precisionAtK(relevant, TOP_K);
        double mrr = mrr(relevant);
        double ndcg = ndcgAtK(relevant, TOP_K);

        // 3. 生成回答 + 裁判打分（仅检索模式跳过，A/B 快速对比）
        if (!withAnswer) {
            return new CaseResult(c.question(), c.docs(), recall, precision, mrr, ndcg, 0, 0, null);
        }
        String answer = ragService.answerSync(c.question(), retrieved);
        String context = buildJudgeContext(retrieved);
        JudgeScores scores = judge(c.question(), context, answer);

        return new CaseResult(c.question(), c.docs(), recall, precision, mrr, ndcg,
                scores.faithfulness(), scores.relevance(), answer);
    }

    private EvalReport aggregate(List<CaseResult> results) {
        int n = results.size();
        double recall = avg(results, CaseResult::recall);
        double precision = avg(results, CaseResult::precision);
        double mrr = avg(results, CaseResult::mrr);
        double ndcg = avg(results, CaseResult::ndcg);

        // 回答质量：只统计裁判成功打分的用例（faithfulness>0）
        long scored = results.stream().filter(r -> r.faithfulness() > 0).count();
        double avgFaith = scored == 0 ? 0
                : results.stream().filter(r -> r.faithfulness() > 0).mapToInt(CaseResult::faithfulness).average().orElse(0);
        double avgRelevance = scored == 0 ? 0
                : results.stream().filter(r -> r.relevance() > 0).mapToInt(CaseResult::relevance).average().orElse(0);
        // 用 find() 而非 matches()（matches 的 . 不匹配换行，会漏算多行回答）
        Pattern citationPattern = Pattern.compile("\\[\\d+\\]");
        double citationRate = n == 0 ? 0
                : (double) results.stream()
                        .filter(r -> r.answer() != null && citationPattern.matcher(r.answer()).find())
                        .count() / n;

        RetrievalMetrics retrieval = new RetrievalMetrics(recall, precision, mrr, ndcg);
        AnswerMetrics answer = new AnswerMetrics(round2(avgFaith), round2(avgRelevance), round2(citationRate));
        return new EvalReport(n, retrieval, answer, results);
    }

    // ==================== 指标计算 ====================

    /** Recall@K = 检出的相关切片数 / 相关切片总数 */
    private double recallAtK(List<Boolean> relevant, int relevantTotal) {
        if (relevantTotal == 0) return 0;
        long hit = relevant.stream().filter(Boolean::booleanValue).count();
        return (double) hit / relevantTotal;
    }

    /** Precision@K = 检出的相关切片数 / K */
    private double precisionAtK(List<Boolean> relevant, int k) {
        if (k == 0 || relevant.isEmpty()) return 0;
        long hit = relevant.stream().limit(k).filter(Boolean::booleanValue).count();
        return (double) hit / k;
    }

    /** MRR = 第一个相关结果排名的倒数（无相关则为 0） */
    private double mrr(List<Boolean> relevant) {
        for (int i = 0; i < relevant.size(); i++) {
            if (relevant.get(i)) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    /** NDCG@K，相关=1、不相关=0 */
    private double ndcgAtK(List<Boolean> relevant, int k) {
        int size = Math.min(k, relevant.size());
        if (size == 0) return 0;
        double dcg = 0;
        int hits = 0;
        for (int i = 0; i < size; i++) {
            if (relevant.get(i)) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                hits++;
            }
        }
        if (hits == 0) return 0;
        double idcg = 0;
        for (int i = 0; i < hits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return dcg / idcg;
    }

    // ==================== LLM 裁判 ====================

    private JudgeScores judge(String question, String context, String answer) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return new JudgeScores(0, 0);
        }
        String prompt = """
                你是严格的 AI 回答质量评测员，只输出 JSON。
                【问题】%s
                【知识库参考内容】%s
                【AI 回答】%s
                请从两方面打分（1-5 整数）：
                1. faithfulness 忠实度：回答是否忠于知识库内容、是否编造了知识库没有的信息
                2. relevance 相关度：回答是否准确、完整地回答了问题
                只输出 JSON 对象：{"faithfulness":X,"relevance":Y}
                """.formatted(question, context, answer);
        // P8-6c：裁判独立于生产生成参数——强制低温度（0.1）降低打分随机性；
        // 解析失败最多重试 1 次（第二次追加更严格的"只输出纯 JSON"约束），避免静默记 0 掩盖低分。
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = chatClient.prompt()
                        .options(OpenAiChatOptions.builder().temperature(0.1).build())
                        .user(prompt)
                        .call()
                        .content();
                JudgeScores s = parseJudge(raw);
                if (s != null) {
                    return s;
                }
            } catch (Exception e) {
                log.warn("裁判调用失败(第{}次): {}", attempt, e.getMessage());
            }
            prompt += "\n注意：必须输出形如 {\"faithfulness\":5,\"relevance\":4} 的纯 JSON，不要输出任何其他文字。";
        }
        return new JudgeScores(0, 0);
    }

    /** 解析裁判 JSON；失败/缺字段/越界返回 null（由调用方决定重试），不再静默当 0 分。 */
    private JudgeScores parseJudge(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            int faith = clampScore(node.path("faithfulness").asInt(-1));
            int rel = clampScore(node.path("relevance").asInt(-1));
            if (faith < 1 || rel < 1) {
                return null;
            }
            return new JudgeScores(faith, rel);
        } catch (Exception e) {
            log.warn("裁判输出解析失败: {}", raw);
            return null;
        }
    }

    private static int clampScore(int v) {
        return (v >= 1 && v <= 5) ? v : -1;
    }

    private String buildJudgeContext(List<Document> retrieved) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Document d : retrieved) {
            sb.append('[').append(i++).append("] ").append(d.getText()).append('\n');
        }
        return sb.toString();
    }

    // ==================== 工具 ====================

    private double avg(List<CaseResult> results, java.util.function.ToDoubleFunction<CaseResult> fn) {
        return results.isEmpty() ? 0 : results.stream().mapToDouble(fn).average().orElse(0);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private Long parseLong(Object o) {
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return -1L;
        }
    }
}

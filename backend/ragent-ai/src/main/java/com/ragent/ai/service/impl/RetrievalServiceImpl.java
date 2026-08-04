package com.ragent.ai.service.impl;

import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.service.DashScopeRerankClient;
import com.ragent.ai.service.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P4 混合检索实现：向量（Qdrant） + 关键词（MySQL FULLTEXT ngram）→ RRF 融合 → DashScope 重排。
 * 每个阶段独立兜底：关键词失败→仅向量；重排失败→用融合结果；向量失败→空列表（不抛给调用方）。
 */
@Slf4j
@Service
public class RetrievalServiceImpl implements RetrievalService {

    /** MySQL BOOLEAN 模式运算符，需从用户查询中剔除，避免 SQL 1064 */
    private static final Pattern BOOLEAN_OP = Pattern.compile("[+\\-<>( )~*\"@]");

    private final VectorStore vectorStore;
    private final DocumentChunkMapper chunkMapper;
    private final DashScopeRerankClient rerankClient;
    private final RetrievalProperties props;

    public RetrievalServiceImpl(VectorStore vectorStore, DocumentChunkMapper chunkMapper,
                                DashScopeRerankClient rerankClient, RetrievalProperties props) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.rerankClient = rerankClient;
        this.props = props;
    }

    @Override
    public List<Document> retrieve(String question, int topK) {
        List<Document> dense = denseSearch(question, props.getDenseTopN());
        List<Document> keyword = props.isKeywordEnabled()
                ? keywordSearch(question, props.getKeywordTopN())
                : List.of();

        List<Document> fused = rrfFuse(dense, keyword, props.getRrfK(), props.getRerankTopN());
        if (!props.isRerankEnabled() || fused.isEmpty()) {
            return truncate(fused, topK);
        }
        try {
            return rerankClient.rerank(question, fused, topK);
        } catch (Exception e) {
            log.warn("重排失败，回退 RRF 融合结果: {}", e.getMessage());
            return truncate(fused, topK);
        }
    }

    // ---------- 各阶段 ----------

    private List<Document> denseSearch(String q, int n) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder().query(q).topK(n).build());
        } catch (Exception e) {
            log.warn("向量检索失败，返回空: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> keywordSearch(String q, int n) {
        String kw = sanitizeKeyword(q);
        if (kw.isBlank()) {
            return List.of();
        }
        try {
            return chunkMapper.keywordSearch(kw, n).stream()
                    .map(this::toDocument)
                    .toList();
        } catch (Exception e) {
            // FULLTEXT 索引缺失（error 1191）或 SQL 异常 → 仅向量检索
            log.warn("关键词检索失败（FULLTEXT 索引缺失？），回退仅向量: {}", e.getMessage());
            return List.of();
        }
    }

    private Document toDocument(DocumentChunkMapper.KeywordRow r) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentId", String.valueOf(r.documentId()));
        meta.put("filename", r.filename());
        meta.put("chunkIndex", r.chunkIndex());
        // 防御：vector_id 缺失时用 documentId:chunkIndex 生成稳定 id，避免 RRF 去重键撞 null
        String id = (r.vectorId() == null || r.vectorId().isBlank())
                ? r.documentId() + ":" + r.chunkIndex()
                : r.vectorId();
        return Document.builder().id(id).text(r.content()).metadata(meta).build();
    }

    // ---------- RRF 融合 ----------

    /** 去重键 = Document.getId()（= Qdrant point id / vector_id），dense 优先保留。 */
    private List<Document> rrfFuse(List<Document> dense, List<Document> keyword, int k, int topN) {
        Map<String, Double> scores = new HashMap<>();
        addRanks(dense, scores, k);
        addRanks(keyword, scores, k);

        Map<String, Document> best = new LinkedHashMap<>();
        for (Document d : dense) {
            best.putIfAbsent(d.getId(), d);
        }
        for (Document d : keyword) {
            best.putIfAbsent(d.getId(), d);
        }

        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        return best.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Document>>comparingDouble(
                        e -> scores.get(e.getKey())).reversed())
                .limit(topN)
                .map(e -> e.getValue().mutate().score(scores.get(e.getKey()) / max).build())
                .toList();
    }

    private void addRanks(List<Document> list, Map<String, Double> scores, int k) {
        int rank = 1;
        for (Document d : list) {
            scores.merge(d.getId(), 1.0 / (k + rank), Double::sum);
            rank++;
        }
    }

    private static List<Document> truncate(List<Document> list, int topK) {
        if (list.size() <= topK) {
            return list;
        }
        return new ArrayList<>(list.subList(0, topK));
    }

    /** 剔除 MySQL 布尔模式运算符，避免用户输入（未闭合引号等）导致 SQL 1064。 */
    private static String sanitizeKeyword(String q) {
        if (q == null) {
            return "";
        }
        return BOOLEAN_OP.matcher(q).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }
}

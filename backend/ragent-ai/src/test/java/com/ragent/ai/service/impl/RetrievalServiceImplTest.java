package com.ragent.ai.service.impl;

import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.DashScopeRerankClient;
import com.ragent.ai.service.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P8-8a：混合检索核心逻辑单元测试（Mockito 桩掉 Qdrant/MySQL/重排）：
 * RRF 排序、实体过滤零召回去过滤重试、重排空结果回退 RRF。
 */
class RetrievalServiceImplTest {

    private RetrievalProperties props;
    private VectorStore vectorStore;
    private KbDocumentMapper kbDocMapper;
    private DashScopeRerankClient rerank;
    private RetrievalService svc;

    @BeforeEach
    void setUp() {
        props = new RetrievalProperties();
        props.setDenseTopN(20);
        props.setKeywordTopN(20);
        props.setTopK(5);
        props.setRrfK(60);
        props.setKeywordEnabled(false); // 测试只走稠密路径
        props.setRerankEnabled(false);
        props.setMinScore(0);
        props.setCacheTtlSeconds(0);

        vectorStore = mock(VectorStore.class);
        kbDocMapper = mock(KbDocumentMapper.class);
        rerank = mock(DashScopeRerankClient.class);
        svc = new RetrievalServiceImpl(vectorStore, mock(DocumentChunkMapper.class),
                kbDocMapper, rerank, props);
    }

    @Test
    void rrfFusionKeepsDenseRankOrder() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("id1", "aaa", 1L), doc("id2", "bbb", 2L)));
        when(kbDocMapper.selectList(any())).thenReturn(List.of(kbDoc(1L), kbDoc(2L)));

        List<Document> r = svc.retrieve("什么是反向传播", 5);

        assertEquals(2, r.size());
        assertEquals("id1", r.get(0).getId());
        assertEquals("id2", r.get(1).getId());
        // RRF 归一化后第一名分数为 1.0
        assertEquals(1.0, r.get(0).getScore(), 1e-6);
    }

    @Test
    void entityFilterZeroRecallRetriesWithoutFilter() {
        // 第一次（带过滤）返回空 → 触发去掉过滤重试，第二次返回结果
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(doc("id1", "aaa", 1L)));
        when(kbDocMapper.selectList(any())).thenReturn(List.of(kbDoc(1L)));

        RetrievalService.EntityHint filter = new RetrievalService.EntityHint("no-such.md", null);
        RetrievalService.RetrievalQuery rq =
                new RetrievalService.RetrievalQuery("q", List.of("q"), List.of("q"), filter, false);

        List<Document> r = svc.retrieve(rq, 5);

        assertEquals(1, r.size());
        assertEquals("id1", r.get(0).getId());
    }

    @Test
    void rerankEmptyResultFallsBackToRrf() {
        props.setRerankEnabled(true);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("id1", "aaa", 1L), doc("id2", "bbb", 2L)));
        when(kbDocMapper.selectList(any())).thenReturn(List.of(kbDoc(1L), kbDoc(2L)));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        List<Document> r = svc.retrieve("q", 5);

        // 重排返回空 → 回退 RRF 融合结果，不整次零召回
        assertEquals(2, r.size());
    }

    private static Document doc(String id, String text, Long documentId) {
        return Document.builder().id(id).text(text)
                .metadata(Map.of("documentId", String.valueOf(documentId))).build();
    }

    private static KbDocument kbDoc(Long id) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus("READY");
        d.setSource("UPLOAD");
        return d;
    }
}

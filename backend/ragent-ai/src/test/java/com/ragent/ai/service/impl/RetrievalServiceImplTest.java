package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.DashScopeRerankClient;
import com.ragent.ai.service.RetrievalService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * P8-8a：混合检索核心逻辑单元测试（Mockito 桩掉 Qdrant/MySQL/重排）：
 * RRF 排序、实体过滤零召回去过滤重试、重排空结果回退 RRF。
 */
class RetrievalServiceImplTest {

    private RetrievalProperties props;
    private VectorStore vectorStore;
    private DocumentChunkMapper chunkMapper;
    private KbDocumentMapper kbDocMapper;
    private DashScopeRerankClient rerank;
    private RetrievalService svc;

    @BeforeEach
    void setUp() {
        initMp();
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
        chunkMapper = mock(DocumentChunkMapper.class);
        kbDocMapper = mock(KbDocumentMapper.class);
        rerank = mock(DashScopeRerankClient.class);
        svc = new RetrievalServiceImpl(vectorStore, chunkMapper, kbDocMapper, rerank, props);
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

    // ==================== P9 多知识库 kbId 贯穿 ====================

    @Test
    void kbIdFlowsToKeywordSearch() {
        props.setKeywordEnabled(true);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(chunkMapper.keywordSearch(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(new DocumentChunkMapper.KeywordRow(
                        "v1", "内容", 1L, 0, "a.md", null, null, null, null, null, null, 1.0)));
        when(kbDocMapper.selectList(any())).thenReturn(List.of(kbDoc(1L)));

        RetrievalService.RetrievalQuery rq =
                new RetrievalService.RetrievalQuery("q", List.of("q"), List.of("q"), null, false, 2L);
        List<Document> r = svc.retrieve(rq, 5);

        // 关键词通道收到 kbId=2（SQL 谓词 d.kb_id = ? 的参数来源）
        ArgumentCaptor<Long> kbCaptor = ArgumentCaptor.forClass(Long.class);
        verify(chunkMapper).keywordSearch(anyString(), anyInt(), any(), kbCaptor.capture());
        assertEquals(2L, kbCaptor.getValue());
        assertEquals(1, r.size());
    }

    @Test
    void kbIdFiltersActiveDocs() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("id1", "aaa", 1L)));
        // selectList 的 wrapper 含 kbId 谓词时返回空（模拟 DB 中该文档不属于目标库）→ 后置过滤生效
        when(kbDocMapper.selectList(any())).thenAnswer(inv -> sqlContainsKbId(inv.getArgument(0))
                ? List.of() : List.of(kbDoc(1L)));

        RetrievalService.RetrievalQuery rq =
                new RetrievalService.RetrievalQuery("q", List.of("q"), List.of("q"), null, false, 2L);
        assertTrue(svc.retrieve(rq, 5).isEmpty()); // 指定库：kb_id 谓词生效 → 过滤掉

        // 全部库（kbId=null）：无 kb_id 谓词 → 保留
        assertEquals(1, svc.retrieve(RetrievalService.RetrievalQuery.single("q"), 5).size());
    }

    @Test
    void cacheKeyIncludesKbId() {
        props.setCacheTtlSeconds(60);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("id1", "aaa", 1L)));
        when(kbDocMapper.selectList(any())).thenReturn(List.of(kbDoc(1L)));

        svc.retrieve(RetrievalService.RetrievalQuery.single("q"), 5); // kbId=null
        svc.retrieve(new RetrievalService.RetrievalQuery("q", List.of("q"), List.of("q"), null, false, 2L), 5);

        // 两次查询仅 kbId 不同：若缓存键不含 kbId，第二次会命中缓存；t=2 证明键确实不同
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void entityFilterRetryKeepsKbId() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of())                                       // 带过滤首查：零召回
                .thenReturn(List.of(doc("id1", "aaa", 1L)));                 // 去过滤重试
        // 重试后 filterActiveDocuments 仍带 kbId 谓词 → 返回空（证明 kbId 被保留而非泄漏跨库）
        when(kbDocMapper.selectList(any())).thenAnswer(inv -> sqlContainsKbId(inv.getArgument(0))
                ? List.of() : List.of(kbDoc(1L)));

        RetrievalService.EntityHint filter = new RetrievalService.EntityHint("no-such.md", null);
        RetrievalService.RetrievalQuery rq =
                new RetrievalService.RetrievalQuery("q", List.of("q"), List.of("q"), filter, false, 2L);

        assertTrue(svc.retrieve(rq, 5).isEmpty());
    }

    /**
     * 校验 MyBatis-Plus wrapper 的 SQL 片段是否包含 kbId 谓词。
     * 单测无 TableInfo 元数据时列名可能回退为驼峰 kbId（运行时为 kb_id），两种都判。
     */
    /**
     * 初始化 MyBatis-Plus 实体元数据：裸 JUnit 无 Spring 上下文时 LambdaQueryWrapper 缺 lambda 列名缓存，
     * getSqlSegment() 会抛 "can not find lambda cache"。这里手动建一次（测试 wrapper 内容用；失败不影响）。
     */
    private static void initMp() {
        try {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);
        } catch (Exception ignored) {
            // 已初始化或环境差异时忽略
        }
    }

    /** 校验 wrapper 的 SQL 片段是否含 kbId 谓词（初始化后列名应为 kb_id；兼容回退 kbId） */
    private static boolean sqlContainsKbId(Object wrapper) {
        if (!(wrapper instanceof com.baomidou.mybatisplus.core.conditions.Wrapper)) {
            return false;
        }
        String sql = ((com.baomidou.mybatisplus.core.conditions.Wrapper<?>) wrapper).getSqlSegment();
        return sql.contains("kb_id") || sql.contains("kbId");
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

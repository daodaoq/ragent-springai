package com.ragent.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragent.ai.entity.DocumentChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 关键词检索（P4 混合检索的关键词通道）。
     * 依赖手动执行的 ngram FULLTEXT 索引 ft_content_ngram；索引缺失时抛 SQL 异常，由调用方降级。
     * 用 BOOLEAN 模式：小语料下自然语言模式的 50% 抑制会杀掉高频 2-gram，布尔模式无此问题，
     * 其精度噪声由 rerank 阶段校正。
     */
    @Select("""
            SELECT dc.vector_id, dc.content, dc.document_id, dc.chunk_index, d.filename,
                   dc.heading_path, dc.line_start, dc.line_end, dc.char_start, dc.char_end, dc.page,
                   MATCH(dc.content) AGAINST(#{keyword} IN BOOLEAN MODE) AS relevance
            FROM document_chunk dc
            JOIN kb_document d ON d.id = dc.document_id AND d.deleted = 0 AND d.status = 'READY'
            WHERE MATCH(dc.content) AGAINST(#{keyword} IN BOOLEAN MODE)
            ORDER BY relevance DESC
            LIMIT #{limit}
            """)
    List<KeywordRow> keywordSearch(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * 关键词命中行。列名与 record 组件通过 map-underscore-to-camel-case 自动映射
     * （依赖父 POM 开启的 -parameters 编译器参数）。
     */
    record KeywordRow(String vectorId, String content, Long documentId,
                      Integer chunkIndex, String filename, String headingPath,
                      Integer lineStart, Integer lineEnd, Integer charStart, Integer charEnd,
                      Integer page, Double relevance) {
    }
}

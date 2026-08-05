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
     * P6 起用 NATURAL LANGUAGE MODE：实测 BOOLEAN MODE 对多词是「全部必含」(AND) 语义，整句查询被 ngram
     * 拆成 5+ 个二元组后几乎必 0 命中（召回全靠 dense+rerank）；NL 模式为 OR + 相关度打分，长句也能召回，
     * 精度噪声由 rerank 阶段校正。
     */
    @Select("""
            SELECT dc.vector_id, dc.content, dc.document_id, dc.chunk_index, d.filename,
                   dc.heading_path, dc.line_start, dc.line_end, dc.char_start, dc.char_end, dc.page,
                   MATCH(dc.content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) AS relevance
            FROM document_chunk dc
            JOIN kb_document d ON d.id = dc.document_id AND d.deleted = 0 AND d.status = 'READY'
            WHERE MATCH(dc.content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE)
              AND (#{filename} IS NULL OR d.filename = #{filename})
              AND (#{kbId} IS NULL OR d.kb_id = #{kbId})
            ORDER BY relevance DESC
            LIMIT #{limit}
            """)
    List<KeywordRow> keywordSearch(@Param("keyword") String keyword, @Param("limit") int limit,
                                   @Param("filename") String filename, @Param("kbId") Long kbId);

    /** 全库关键词检索（filename/kbId 过滤缺省） */
    default List<KeywordRow> keywordSearch(String keyword, int limit) {
        return keywordSearch(keyword, limit, null, null);
    }

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

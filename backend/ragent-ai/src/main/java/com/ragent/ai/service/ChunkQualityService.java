package com.ragent.ai.service;

import java.util.List;

/**
 * 切片质量评估：全局切片参数设置（前端可改）+ 每文档/全库质量报告（供「切片质量」页可视化）。
 */
public interface ChunkQualityService {

    /** 全局切片参数（kb_chunk_settings 优先，缺省回落 yml 默认） */
    ChunkSettings getSettings();

    /** 更新全局切片参数（upsert 单行） */
    void updateSettings(ChunkSettings settings);

    /**
     * 质量报告。docId 非空 → 单文档；为空 → 全 READY 文档聚合 + 明细。
     * 数据来自 document_chunk 实际切片，不重新切分。
     */
    ChunkQualityReport qualityReport(Long docId);

    /** 全局切片参数 */
    record ChunkSettings(int maxChunkChars, int overlapChars, boolean semanticEnabled) {
    }

    /** 切片长度直方图的一个桶（[start, end) 字符区间） */
    record Bucket(int start, int end, int count) {
    }

    /** 单文档质量明细 */
    record DocQuality(Long docId, String filename, String status, int chunkCount,
                      Integer maxChunkChars, Integer overlapChars, Boolean semantic,
                      double avgLen, int minLen, int maxLen,
                      long tooShort, long overlong, long noHeading, long duplicate, long missingVector,
                      boolean countMismatch) {
    }

    /** 聚合质量报告 */
    record ChunkQualityReport(int docCount, int totalChunks, double avgChunkLen,
                              long tooShortCount, long overlongCount, long noHeadingCount,
                              long duplicateCount, long missingVectorCount,
                              List<Bucket> lengthBuckets, List<DocQuality> docs) {
    }
}

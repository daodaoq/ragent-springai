package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.ai.config.ChunkingProperties;
import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.KbChunkSettings;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.mapper.KbChunkSettingsMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.ChunkQualityService;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 切片质量评估实现。指标基于 document_chunk 实际数据聚合：
 * 过短(<50字符)/过长(>文档生效 maxChunkChars×1.5)/无标题上下文(heading_path 空)/
 * 重复内容(md5 文本)/缺失向量(vector_id 空)/chunk_count 与实际行数不一致。
 */
@Service
@RequiredArgsConstructor
public class ChunkQualityServiceImpl implements ChunkQualityService {

    /** 过短切片阈值（字符） */
    private static final int MIN_CHUNK_CHARS = 50;
    /** 过长判定系数：> maxChunkChars × 1.5 */
    private static final double OVERLONG_FACTOR = 1.5;
    /** 直方图桶宽（字符） */
    private static final int BUCKET_SIZE = 100;

    private final KbChunkSettingsMapper chunkSettingsMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ChunkingProperties chunkingProps;

    @Override
    public ChunkSettings getSettings() {
        KbChunkSettings s = chunkSettingsMapper.selectById(1);
        if (s == null || s.getMaxChunkChars() == null || s.getOverlapChars() == null) {
            return new ChunkSettings(chunkingProps.getMaxChunkChars(), chunkingProps.getOverlapChars(),
                    chunkingProps.isSemanticEnabled());
        }
        return new ChunkSettings(s.getMaxChunkChars(), s.getOverlapChars(),
                Boolean.TRUE.equals(s.getSemanticEnabled()));
    }

    @Override
    public void updateSettings(ChunkSettings settings) {
        if (settings.maxChunkChars() < 100 || settings.maxChunkChars() > 4000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "单切片最大字符数需在 100~4000 之间");
        }
        if (settings.overlapChars() < 0 || settings.overlapChars() > settings.maxChunkChars()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重叠字符数需在 0~maxChunkChars 之间");
        }
        KbChunkSettings s = chunkSettingsMapper.selectById(1);
        if (s == null) {
            s = new KbChunkSettings();
            s.setId(1);
        }
        s.setMaxChunkChars(settings.maxChunkChars());
        s.setOverlapChars(settings.overlapChars());
        s.setSemanticEnabled(settings.semanticEnabled());
        chunkSettingsMapper.insertOrUpdate(s);
    }

    @Override
    public ChunkQualityReport qualityReport(Long docId) {
        List<KbDocument> docs;
        if (docId != null) {
            KbDocument d = kbDocumentMapper.selectById(docId);
            if (d == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
            }
            docs = List.of(d);
        } else {
            docs = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                    .eq(KbDocument::getStatus, "READY")
                    .orderByDesc(KbDocument::getCreatedAt));
        }

        KbChunkSettings settings = chunkSettingsMapper.selectById(1);
        List<DocumentChunk> chunks = loadChunks(docs);
        Map<Long, List<DocumentChunk>> byDoc = chunks.stream()
                .collect(Collectors.groupingBy(DocumentChunk::getDocumentId));

        List<DocQuality> qualities = new ArrayList<>();
        int totalChunks = 0;
        long tooShortAll = 0, overlongAll = 0, noHeadingAll = 0, dupAll = 0, missingVecAll = 0;
        List<Integer> allLens = new ArrayList<>();
        for (KbDocument d : docs) {
            List<DocumentChunk> docChunks = byDoc.getOrDefault(d.getId(), List.of());
            DocQuality q = computeDocQuality(d, docChunks, settings);
            qualities.add(q);
            totalChunks += docChunks.size();
            tooShortAll += q.tooShort();
            overlongAll += q.overlong();
            noHeadingAll += q.noHeading();
            dupAll += q.duplicate();
            missingVecAll += q.missingVector();
            for (DocumentChunk c : docChunks) {
                allLens.add(c.getContent() == null ? 0 : c.getContent().length());
            }
        }
        double avgLen = allLens.isEmpty() ? 0
                : allLens.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new ChunkQualityReport(docs.size(), totalChunks, round1(avgLen),
                tooShortAll, overlongAll, noHeadingAll, dupAll, missingVecAll,
                buildBuckets(allLens), qualities);
    }

    private List<DocumentChunk> loadChunks(List<KbDocument> docs) {
        List<Long> ids = docs.stream().map(KbDocument::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>().in(DocumentChunk::getDocumentId, ids));
    }

    private DocQuality computeDocQuality(KbDocument d, List<DocumentChunk> chunks, KbChunkSettings settings) {
        int maxChars = resolveMaxChars(d, settings);
        long sum = 0, tooShort = 0, overlong = 0, noHeading = 0, missing = 0, dup = 0;
        int minLen = chunks.isEmpty() ? 0 : Integer.MAX_VALUE;
        int maxLen = 0;
        Set<String> seen = new HashSet<>();
        for (DocumentChunk c : chunks) {
            String content = c.getContent();
            int len = content == null ? 0 : content.length();
            sum += len;
            minLen = Math.min(minLen, len);
            maxLen = Math.max(maxLen, len);
            if (len < MIN_CHUNK_CHARS) {
                tooShort++;
            }
            if (len > maxChars * OVERLONG_FACTOR) {
                overlong++;
            }
            if (c.getHeadingPath() == null || c.getHeadingPath().isBlank()) {
                noHeading++;
            }
            if (c.getVectorId() == null || c.getVectorId().isBlank()) {
                missing++;
            }
            if (content != null && !content.isBlank() && !seen.add(content)) {
                dup++;
            }
        }
        double avg = chunks.isEmpty() ? 0 : (double) sum / chunks.size();
        boolean mismatch = d.getChunkCount() != null && !chunks.isEmpty() && chunks.size() != d.getChunkCount();
        return new DocQuality(d.getId(), d.getFilename(), d.getStatus(), chunks.size(),
                d.getChunkMaxChars(), d.getChunkOverlapChars(), d.getChunkSemantic(),
                round1(avg), minLen, maxLen, tooShort, overlong, noHeading, dup, missing, mismatch);
    }

    /** 每文档生效的 maxChunkChars：文档覆盖 > 全局设置 > yml 默认 */
    private int resolveMaxChars(KbDocument d, KbChunkSettings settings) {
        if (d.getChunkMaxChars() != null) {
            return d.getChunkMaxChars();
        }
        if (settings != null && settings.getMaxChunkChars() != null) {
            return settings.getMaxChunkChars();
        }
        return chunkingProps.getMaxChunkChars();
    }

    /** 切片长度直方图（100 字符/桶，上限取最大长度+一桶） */
    private List<Bucket> buildBuckets(List<Integer> lens) {
        if (lens.isEmpty()) {
            return List.of(new Bucket(0, BUCKET_SIZE, 0));
        }
        int maxBound = Collections.max(lens) + BUCKET_SIZE;
        int n = (int) Math.ceil((double) maxBound / BUCKET_SIZE);
        int[] counts = new int[n];
        for (int l : lens) {
            counts[Math.min(l / BUCKET_SIZE, n - 1)]++;
        }
        List<Bucket> buckets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            buckets.add(new Bucket(i * BUCKET_SIZE, (i + 1) * BUCKET_SIZE, counts[i]));
        }
        return buckets;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

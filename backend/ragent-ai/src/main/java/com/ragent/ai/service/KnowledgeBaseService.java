package com.ragent.ai.service;

import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.KbDocument;
import com.ragent.common.result.PageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库服务：文档上传→解析→切分→向量化→入 Qdrant；列表/删除
 */
public interface KnowledgeBaseService {

    KbDocument upload(MultipartFile file);

    /** 单文件上传，可携带该文件的切片参数覆盖（null 字段 = 用全局默认） */
    KbDocument upload(MultipartFile file, ChunkParams params);

    /**
     * 批量上传：一次接收多个文件，后端线程池并行处理 + 嵌入按 10 条/批自动拆分，
     * 每个文件独立返回结果（单个失败不拖垮整批）。params 与 files 按下标对齐，可为 null。
     */
    List<UploadResult> uploadBatch(List<MultipartFile> files, List<ChunkParams> params);

    /**
     * 重新切片：从 MinIO 读原始文件，用新参数覆盖重新切分+向量化（先清旧切片/向量）。
     * params 全为 null 表示重置回全局默认。
     */
    KbDocument rechunk(Long id, ChunkParams params);

    /**
     * 重试处理失败的文档：从 MinIO 读取已保存的原始文件重新切分+向量化，无需重新上传。
     * 仅 FAILED 文档可重试；原始文件未保存（objectKey 为空）时提示重新上传。
     */
    KbDocument retry(Long id);

    /** 供评测程序注入文本文档（幂等：同名已存在则跳过） */
    KbDocument uploadTextIfAbsent(String filename, String text);

    List<KbDocument> list();

    /** 文档切片分页查看（按 chunk_index 升序） */
    PageResult<DocumentChunk> chunks(Long id, int pageNum, int pageSize);

    /** 查看原文：从 MinIO 读取原始文件并提取文本。未保存原始文件的旧数据返回提示重新上传 */
    SourceText getSource(Long id);

    void delete(Long id);

    /** 批量删除多个文档（含切片、向量、MinIO 原始文件） */
    void deleteBatch(List<Long> ids);

    /** 批量上传的单文件结果 */
    record UploadResult(String filename, boolean success, String message) {}

    /** 切片参数（上传携带 / 重新切片）：null 字段 = 用全局默认（rechunk 时=重置回全局） */
    record ChunkParams(Integer maxChunkChars, Integer overlapChars, Boolean semantic) {}

    /** 原文内容：filename + 提取后的全文文本 + 行数（含文件名/类型信息） */
    record SourceText(String filename, String contentType, String text, int lineCount) {}
}

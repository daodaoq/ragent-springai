package com.ragent.ai.service;

import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.KbDocument;
import com.ragent.common.result.PageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库服务：文档入队→异步解析/切分/向量化（P9-5a 任务队列）+ 列表/删除。
 * 上传/重切/重试只做"校验 + 落 MinIO + 写 ingest_task"，立即返回；真正的处理由
 * {@link IngestTaskService} 的轮询 worker 消费，失败自动重试（瞬时）或进 DLQ（永久）。
 */
public interface KnowledgeBaseService {

    /** 单文件上传：校验+落 MinIO+入队（异步处理），立即返回 PENDING 文档。kbId 为空 = 默认知识库。 */
    KbDocument enqueueUpload(MultipartFile file, ChunkParams params, Long kbId);

    /**
     * 批量上传：逐文件快速入队，立即返回结果（success=已入队，带 documentId 供前端跟踪）。
     * 单个文件入队失败只在结果里标记，不影响其他文件。kbId 为空 = 默认知识库。
     */
    List<UploadResult> enqueueUploadBatch(List<MultipartFile> files, List<ChunkParams> params, Long kbId);

    /**
     * 重新切片入队：新参数覆盖重新切分+向量化（异步；worker 先清旧切片/向量）。
     * params 全为 null 表示重置回全局默认。返回 PENDING 文档。
     */
    KbDocument enqueueRechunk(Long id, ChunkParams params);

    /**
     * 重试处理入队：从 MinIO 读原始文件重新切分+向量化（异步）。仅非 READY 文档可重试。
     * 原始文件未保存（objectKey 为空）时提示重新上传。返回 PENDING 文档。
     */
    KbDocument enqueueRetry(Long id);

    /**
     * 异步 worker 实际处理：按任务类型驱动——
     * UPLOAD：处理成功后删除旧的同名文档（两阶段替换）；RECHUNK/RETRY：先清旧切片/向量再重跑。
     * 文档已被删除时返回 null（调用方标记任务跳过，避免空转）。
     */
    KbDocument processDocument(Long documentId, String taskType);

    /** 供评测程序注入文本文档（同步处理，幂等：同名已存在则跳过） */
    KbDocument uploadTextIfAbsent(String filename, String text);

    /** P8-6c：标记文档来源（UPLOAD/EVAL）。评测注入后调用，生产检索据此排除 EVAL 样例 */
    void markSource(Long id, String source);

    /** 文档列表（仅 UPLOAD 来源）；kbId 非空时限定该库 */
    List<KbDocument> list(Long kbId);

    /** 全库文档列表（等价 list(null)） */
    List<KbDocument> list();

    /** 文档切片分页查看（按 chunk_index 升序） */
    PageResult<DocumentChunk> chunks(Long id, int pageNum, int pageSize);

    /** 查看原文：从 MinIO 读取原始文件并提取文本。未保存原始文件的旧数据返回提示重新上传 */
    SourceText getSource(Long id);

    void delete(Long id);

    /** 批量删除多个文档（含切片、向量、MinIO 原始文件、未消费任务） */
    void deleteBatch(List<Long> ids);

    /** 批量上传的单文件入队结果（success=已入队；documentId 为新建文档 ID，失败为 null） */
    record UploadResult(String filename, boolean success, String message, Long documentId) {}

    /** 切片参数（上传携带 / 重新切片）：null 字段 = 用全局默认（rechunk 时=重置回全局） */
    record ChunkParams(Integer maxChunkChars, Integer overlapChars, Boolean semantic) {}

    /** 原文内容：filename + 提取后的全文文本 + 行数（含文件名/类型信息） */
    record SourceText(String filename, String contentType, String text, int lineCount) {}
}

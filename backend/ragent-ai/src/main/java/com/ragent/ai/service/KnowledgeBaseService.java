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

    /**
     * 批量上传：一次接收多个文件，后端线程池并行处理 + 嵌入按 10 条/批自动拆分，
     * 每个文件独立返回结果（单个失败不拖垮整批）。
     */
    List<UploadResult> uploadBatch(List<MultipartFile> files);

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

    void delete(Long id);

    /** 批量上传的单文件结果 */
    record UploadResult(String filename, boolean success, String message) {}
}

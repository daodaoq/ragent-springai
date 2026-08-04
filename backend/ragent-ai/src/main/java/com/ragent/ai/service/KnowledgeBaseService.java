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

    /** 供评测程序注入文本文档（幂等：同名已存在则跳过） */
    KbDocument uploadTextIfAbsent(String filename, String text);

    List<KbDocument> list();

    /** 文档切片分页查看（按 chunk_index 升序） */
    PageResult<DocumentChunk> chunks(Long id, int pageNum, int pageSize);

    void delete(Long id);
}

package com.ragent.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.web.entity.DocumentChunk;
import com.ragent.web.entity.KbDocument;
import com.ragent.web.mapper.DocumentChunkMapper;
import com.ragent.web.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库服务：文档上传→解析→切分→向量化→入 Qdrant；列表/删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final KbDocumentMapper kbDocumentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final VectorStore vectorStore;

    public KbDocument upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能超过 10MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        try {
            return process(filename, file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }
    }

    /** 供评测程序注入文本文档（幂等：同名已存在则跳过） */
    public KbDocument uploadTextIfAbsent(String filename, String text) {
        KbDocument exist = kbDocumentMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getFilename, filename));
        if (exist != null && "READY".equals(exist.getStatus())) {
            return exist;
        }
        if (exist != null) {
            delete(exist.getId());
        }
        return process(filename, "text/markdown", text.getBytes(StandardCharsets.UTF_8));
    }

    public List<KbDocument> list() {
        return kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .orderByDesc(KbDocument::getCreatedAt));
    }

    public void delete(Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id));
        List<String> vectorIds = chunks.stream().map(DocumentChunk::getVectorId).toList();
        if (!vectorIds.isEmpty()) {
            try {
                vectorStore.delete(vectorIds);
            } catch (Exception e) {
                // 向量不存在/集合不存在时删除本就是幂等操作，忽略继续
                log.warn("删除 Qdrant 向量失败（忽略继续）: {}", e.getMessage());
            }
        }
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id));
        kbDocumentMapper.deleteById(id);
    }

    private KbDocument process(String filename, String contentType, byte[] bytes) {
        KbDocument doc = new KbDocument();
        doc.setFilename(filename);
        doc.setContentType(contentType);
        doc.setSize(bytes.length);
        doc.setStatus("PENDING");
        kbDocumentMapper.insert(doc);
        try {
            String text = extractText(filename, bytes);
            if (text.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未提取到文本内容");
            }
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(500)
                    .withKeepSeparator(true)
                    .build();
            List<Document> chunks = splitter.split(new Document(text));

            List<Document> toStore = new ArrayList<>();
            int idx = 0;
            for (Document chunk : chunks) {
                Map<String, Object> meta = new HashMap<>();
                // Qdrant payload 不支持 Long，雪花 ID 转 String 存储
                meta.put("documentId", String.valueOf(doc.getId()));
                meta.put("filename", filename);
                meta.put("chunkIndex", idx);
                chunk.getMetadata().putAll(meta);

                DocumentChunk dc = new DocumentChunk();
                dc.setDocumentId(doc.getId());
                dc.setContent(chunk.getText());
                dc.setChunkIndex(idx);
                dc.setVectorId(chunk.getId());
                documentChunkMapper.insert(dc);

                toStore.add(chunk);
                idx++;
            }
            vectorStore.add(toStore);

            doc.setChunkCount(toStore.size());
            doc.setStatus("READY");
            kbDocumentMapper.updateById(doc);
            log.info("知识库文档已入库: {} ({} 切片)", filename, toStore.size());
            return doc;
        } catch (Exception e) {
            log.error("知识库文档处理失败: {}", filename, e);
            doc.setStatus("FAILED");
            kbDocumentMapper.updateById(doc);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文档处理失败: " + e.getMessage());
        }
    }

    private String extractText(String filename, byte[] bytes) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    new ByteArrayResource(bytes, filename));
            List<Document> docs = reader.get();
            StringBuilder sb = new StringBuilder();
            for (Document d : docs) {
                sb.append(d.getText()).append('\n');
            }
            return sb.toString();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

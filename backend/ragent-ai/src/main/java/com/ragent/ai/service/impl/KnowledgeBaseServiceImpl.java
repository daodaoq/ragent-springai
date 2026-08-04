package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.ChunkingService;
import com.ragent.ai.service.KnowledgeBaseService;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.result.PageResult;
import com.ragent.common.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库服务实现：文档上传→解析→切分→向量化→入 Qdrant；列表/删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * DashScope 兼容模式的嵌入接口单次最多接收 10 条文本（超过报 400 batch size is invalid）。
     * 一个文档的切片数可能远超 10，必须按此上限分批调用向量化。
     */
    private static final int MAX_EMBED_BATCH = 10;

    /** 批量上传时并行处理文件数：真正的多线程，又不至于同时打爆向量化接口 */
    private static final int FILE_CONCURRENCY = 4;

    /** 批量上传线程池（应用生命周期内常驻） */
    private static final ExecutorService UPLOAD_EXECUTOR = Executors.newFixedThreadPool(FILE_CONCURRENCY);

    /** 从服务端错误 JSON 中提取 message 字段，避免把整段原始 JSON 抛给前端 */
    private static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final KbDocumentMapper kbDocumentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final VectorStore vectorStore;
    private final ChunkingService chunkingService;
    private final MinioStorageService minioStorage;

    @Override
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
        // 同名处理：直接覆盖重传（视为更新该文档）。先删旧文档（含切片/向量/MinIO 原始文件），
        // 避免重传同名文件时报「已存在」，也顺带清掉上次失败/PENDING 的残留。
        KbDocument exist = kbDocumentMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getFilename, filename));
        if (exist != null) {
            delete(exist.getId());
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }

        KbDocument doc = new KbDocument();
        doc.setFilename(filename);
        doc.setContentType(file.getContentType());
        doc.setSize(bytes.length);
        doc.setStatus("PENDING");
        kbDocumentMapper.insert(doc);

        // 先落 MinIO 再处理：即使后续向量化失败，原始文件也已保存，可直接重试而无需重新上传
        String objectKey = "kb/" + doc.getId() + "/" + sanitizeFilename(filename);
        try {
            minioStorage.put(objectKey, bytes, file.getContentType());
        } catch (Exception e) {
            doc.setStatus("FAILED");
            kbDocumentMapper.updateById(doc);
            throw e;
        }
        doc.setObjectKey(objectKey);
        kbDocumentMapper.updateById(doc);

        return process(doc, bytes);
    }

    @Override
    public KbDocument retry(Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if ("READY".equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档已处理完成，无需重试");
        }
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该文档未保存原始文件（旧数据），请删除后重新上传");
        }
        byte[] bytes = minioStorage.get(doc.getObjectKey());

        // 清理上次失败留下的切片和向量，避免重试后数据重复
        List<DocumentChunk> oldChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id));
        List<String> vectorIds = oldChunks.stream().map(DocumentChunk::getVectorId).toList();
        if (!vectorIds.isEmpty()) {
            try {
                vectorStore.delete(vectorIds);
            } catch (Exception e) {
                log.warn("重试清理旧向量失败（忽略继续）: {}", e.getMessage());
            }
        }
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id));

        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        kbDocumentMapper.updateById(doc);
        log.info("重试文档处理: {} (id={})", doc.getFilename(), id);
        return process(doc, bytes);
    }

    @Override
    public List<UploadResult> uploadBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        // 有界线程池并行处理多个文件：文件间多线程，文件内嵌入按 10 条/批串行调用，
        // 单个文件失败只在结果里标记，不影响其他文件入库。
        List<Future<UploadResult>> futures = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            futures.add(UPLOAD_EXECUTOR.submit(() -> safeUpload(file)));
        }
        List<UploadResult> results = new ArrayList<>(futures.size());
        for (Future<UploadResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "批量上传被中断");
            } catch (ExecutionException e) {
                // safeUpload 已吞掉所有异常，这里仅兜底
                Throwable cause = e.getCause();
                results.add(new UploadResult("未知文件", false,
                        friendlyError(cause == null ? e.getMessage() : cause.getMessage())));
            }
        }
        return results;
    }

    /** 单文件上传的异常隔离：任何失败都转成结果对象，不让线程池任务抛异常 */
    private UploadResult safeUpload(MultipartFile file) {
        String filename = file.getOriginalFilename();
        try {
            upload(file);
            return new UploadResult(filename, true, null);
        } catch (Exception e) {
            log.warn("批量上传单个文件失败: {} - {}", filename, e.getMessage());
            return new UploadResult(filename, false, friendlyError(e.getMessage()));
        }
    }

    /** 提取服务端错误 JSON 的 message 字段并截断，避免把整段原始 JSON 抛给前端 */
    private String friendlyError(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知错误";
        }
        Matcher m = ERROR_MESSAGE_PATTERN.matcher(raw);
        String msg = m.find() ? m.group(1) : raw;
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    /** 文件名清洗：去掉目录路径，仅保留文件名，避免 MinIO key 出现异常层级 */
    private String sanitizeFilename(String filename) {
        String safe = filename.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1);
        return safe.trim();
    }

    @Override
    public KbDocument uploadTextIfAbsent(String filename, String text) {
        KbDocument exist = kbDocumentMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getFilename, filename));
        if (exist != null && "READY".equals(exist.getStatus())) {
            return exist;
        }
        if (exist != null) {
            delete(exist.getId());
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        KbDocument doc = new KbDocument();
        doc.setFilename(filename);
        doc.setContentType("text/markdown");
        doc.setSize(bytes.length);
        doc.setStatus("PENDING");
        kbDocumentMapper.insert(doc);
        return process(doc, bytes);
    }

    @Override
    public List<KbDocument> list() {
        return kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .orderByDesc(KbDocument::getCreatedAt));
    }

    @Override
    public PageResult<DocumentChunk> chunks(Long id, int pageNum, int pageSize) {
        if (kbDocumentMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        Page<DocumentChunk> page = documentChunkMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, id)
                        .orderByAsc(DocumentChunk::getChunkIndex));
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    @Override
    public void delete(Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        // 删除 MinIO 原始文件（幂等，失败不影响主流程）
        if (doc.getObjectKey() != null && !doc.getObjectKey().isBlank()) {
            minioStorage.delete(doc.getObjectKey());
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

    private KbDocument process(KbDocument doc, byte[] bytes) {
        String filename = doc.getFilename();
        try {
            String text = extractText(filename, bytes);
            if (text.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未提取到文本内容");
            }
            // 结构感知分片：按标题分节 + 段落组块 + 句子边界 + 重叠
            List<ChunkingService.Chunk> chunks = chunkingService.chunk(text);

            List<Document> toStore = new ArrayList<>();
            int idx = 0;
            for (ChunkingService.Chunk chunk : chunks) {
                Map<String, Object> meta = new HashMap<>();
                // Qdrant payload 不支持 Long，雪花 ID 转 String 存储
                meta.put("documentId", String.valueOf(doc.getId()));
                meta.put("filename", filename);
                meta.put("chunkIndex", idx);
                meta.put("title", chunk.title());

                Document entry = new Document(chunk.content(), meta);
                DocumentChunk dc = new DocumentChunk();
                dc.setDocumentId(doc.getId());
                dc.setContent(chunk.content());
                dc.setChunkIndex(idx);
                dc.setVectorId(entry.getId());
                documentChunkMapper.insert(dc);

                toStore.add(entry);
                idx++;
            }
            // DashScope 兼容嵌入接口单次最多 10 条文本（超过报 400 batch size invalid）。
            // 切片数往往远超 10，按上限分批向量化入 Qdrant，保证大文档正常入库。
            for (int i = 0; i < toStore.size(); i += MAX_EMBED_BATCH) {
                vectorStore.add(toStore.subList(i, Math.min(i + MAX_EMBED_BATCH, toStore.size())));
            }

            doc.setChunkCount(toStore.size());
            doc.setStatus("READY");
            kbDocumentMapper.updateById(doc);
            log.info("知识库文档已入库: {} ({} 切片)", filename, toStore.size());
            return doc;
        } catch (Exception e) {
            log.error("知识库文档处理失败: {}", filename, e);
            doc.setStatus("FAILED");
            kbDocumentMapper.updateById(doc);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文档处理失败: " + friendlyError(e.getMessage()));
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

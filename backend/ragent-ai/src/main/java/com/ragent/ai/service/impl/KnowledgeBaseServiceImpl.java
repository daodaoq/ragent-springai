package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragent.ai.config.ChunkingProperties;
import com.ragent.ai.config.IngestProperties;
import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.IngestTask;
import com.ragent.ai.entity.KbChunkSettings;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.mapper.IngestTaskMapper;
import com.ragent.ai.mapper.KbChunkSettingsMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.AiRetry;
import com.ragent.ai.service.ChunkingService;
import com.ragent.ai.service.KbService;
import com.ragent.ai.service.KnowledgeBaseService;
import com.ragent.ai.service.RetrievalService;
import com.ragent.ai.service.ingest.KbFilenameLock;
import com.ragent.common.context.RagentContext;
import com.ragent.common.context.RagentThreadPools;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.result.PageResult;
import com.ragent.common.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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

    /** P8-8c：知识库上传允许的扩展名白名单（DocumentTextExtractor 实际能解析的格式） */
    private static final Set<String> ALLOWED_KB_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "html", "htm",
            "pdf", "doc", "docx", "rtf", "xlsx", "pptx");

    /**
     * DashScope 兼容模式的嵌入接口单次最多接收 10 条文本（超过报 400 batch size is invalid）。
     * 一个文档的切片数可能远超 10，必须按此上限分批调用向量化。
     */
    private static final int MAX_EMBED_BATCH = 10;

    /** 批量上传时并行处理文件数：真正的多线程，又不至于同时打爆向量化接口 */
    private static final int FILE_CONCURRENCY = 4;

    /** 批量上传线程池（应用生命周期内常驻；TTL+MDC 透传，线程名 kb-upload） */
    private static final ExecutorService UPLOAD_EXECUTOR = RagentThreadPools.newExecutor("kb-upload",
            FILE_CONCURRENCY, FILE_CONCURRENCY, 100, new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /** 从服务端错误 JSON 中提取 message 字段，避免把整段原始 JSON 抛给前端 */
    private static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final KbDocumentMapper kbDocumentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final KbChunkSettingsMapper chunkSettingsMapper;
    private final IngestTaskMapper ingestTaskMapper;
    private final VectorStore vectorStore;
    private final ChunkingService chunkingService;
    private final MinioStorageService minioStorage;
    private final DocumentTextExtractor textExtractor;
    private final ChunkingProperties chunkingProps;
    private final IngestProperties ingestProps;
    private final RetrievalService retrievalService;
    private final KbFilenameLock kbFilenameLock;
    private final KbService kbService;

    @Override
    public KbDocument enqueueUpload(MultipartFile file, ChunkParams params, Long kbId) {
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
        // P8-8c：扩展名白名单，拒绝任意文件落库（防上传可执行文件/超大非文本等）
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_KB_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型（." + ext + "），支持: " + String.join("/", ALLOWED_KB_EXTENSIONS));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }

        String newHash = sha256Hex(bytes);
        // P8-8e：同名查找容忍历史重复行（并发批量上传遗留），取最新一条作为"当前"文档
        KbDocument exist = findLatestByFilename(filename);
        // P8-5b：内容级幂等——同名同内容且已 READY 直接返回，跳过重复切分/向量化
        // （fileHash 此前只算不用，这里让它真正参与变更检测）
        if (exist != null && "READY".equals(exist.getStatus()) && newHash.equals(exist.getFileHash())) {
            log.info("同名同内容文档已存在，跳过重复处理: {} (id={})", filename, exist.getId());
            return exist;
        }

        KbDocument doc = new KbDocument();
        doc.setFilename(filename);
        doc.setContentType(file.getContentType());
        doc.setSize(bytes.length);
        doc.setStatus("PENDING");
        doc.setFileHash(newHash); // 内容哈希：同名文件判断是否真改内容、变更检测
        // P9：归属知识库——未指定用默认库；并校验库存在（避免文档挂到不存在的库上）
        Long targetKb = kbId != null ? kbId : kbService.getDefaultKbId();
        if (targetKb != null) {
            kbService.requireById(targetKb);
            doc.setKbId(targetKb);
        }
        applyChunkParams(doc, params); // 上传携带的切片参数覆盖（null 字段不动）
        kbDocumentMapper.insert(doc);

        // 先落 MinIO 再入队：即使后续处理失败，原始文件也已保存，可直接重试而无需重新上传
        String objectKey = "kb/" + doc.getId() + "/" + sanitizeFilename(filename);
        try {
            minioStorage.put(objectKey, bytes, file.getContentType());
        } catch (Exception e) {
            doc.setStatus("FAILED");
            doc.setErrorMsg(friendlyError(e.getMessage()));
            kbDocumentMapper.updateById(doc);
            throw e;
        }
        doc.setObjectKey(objectKey);
        kbDocumentMapper.updateById(doc);

        // P9-5a 异步化：真正的解析/切分/向量化交给 ingest_task 队列 worker。
        // 两阶段替换（新文档 READY 后删旧同名文档）移入 processDocument（worker 在文件名锁内串行执行）。
        enqueue(IngestTask.TYPE_UPLOAD, doc.getId());
        return doc;
    }

    /** 写一条入队任务；失败时把文档置 FAILED（防 PENDING 无任务卡死），并上抛。 */
    private void enqueue(String taskType, Long documentId) {
        IngestTask task = new IngestTask();
        task.setDocumentId(documentId);
        task.setTaskType(taskType);
        task.setStatus(IngestTask.STATUS_QUEUED);
        task.setAttempt(0);
        task.setMaxAttempts(ingestProps.getMaxAttempts());
        RagentContext ctx = RagentContext.current();
        if (ctx != null) {
            task.setTraceId(ctx.traceId());
        }
        try {
            ingestTaskMapper.insert(task);
        } catch (Exception e) {
            log.error("摄取任务入队失败: docId={} type={}", documentId, taskType, e);
            KbDocument doc = kbDocumentMapper.selectById(documentId);
            if (doc != null) {
                doc.setStatus("FAILED");
                doc.setErrorMsg("任务入队失败");
                kbDocumentMapper.updateById(doc);
            }
            throw e;
        }
    }

    @Override
    public KbDocument enqueueRetry(Long id) {
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
        // 异步化：只入队；worker 的 processDocument(RETRY) 会先清旧切片/向量再重跑
        doc.setStatus("PENDING");
        doc.setErrorMsg(null);
        doc.setChunkCount(0);
        kbDocumentMapper.updateById(doc);
        log.info("重试文档处理已入队: {} (id={})", doc.getFilename(), id);
        enqueue(IngestTask.TYPE_RETRY, id);
        return doc;
    }

    /** 便捷重载（无参数覆盖），接口只声明带 params 的版本 */
    public List<UploadResult> enqueueUploadBatch(List<MultipartFile> files) {
        return enqueueUploadBatch(files, null, null);
    }

    @Override
    public List<UploadResult> enqueueUploadBatch(List<MultipartFile> files, List<ChunkParams> params, Long kbId) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        // P9-5a：有界线程池并行"快速入队"（校验+落 MinIO+写任务表，秒回）；
        // 真正的解析/切分/向量化由异步队列 worker 消费。单文件入队失败只在结果里标记。
        List<Future<UploadResult>> futures = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            ChunkParams p = (params != null && i < params.size()) ? params.get(i) : null;
            futures.add(UPLOAD_EXECUTOR.submit(() -> safeEnqueue(file, p, kbId)));
        }
        List<UploadResult> results = new ArrayList<>(futures.size());
        for (Future<UploadResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "批量上传被中断");
            } catch (ExecutionException e) {
                // safeEnqueue 已吞掉所有异常，这里仅兜底
                Throwable cause = e.getCause();
                results.add(new UploadResult("未知文件", false,
                        friendlyError(cause == null ? e.getMessage() : cause.getMessage()), null));
            }
        }
        return results;
    }

    /** 单文件入队的异常隔离：任何失败都转成结果对象，不让线程池任务抛异常 */
    private UploadResult safeEnqueue(MultipartFile file, ChunkParams params, Long kbId) {
        String filename = file.getOriginalFilename();
        try {
            KbDocument doc = enqueueUpload(file, params, kbId);
            return new UploadResult(filename, true, null, doc.getId());
        } catch (Exception e) {
            log.warn("批量上传单个文件入队失败: {} - {}", filename, e.getMessage());
            return new UploadResult(filename, false, friendlyError(e.getMessage()), null);
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

    /**
     * 按文件名取最新一条文档。用 selectOne + LIMIT 1 而非裸 selectOne：
     * 历史并发批量上传可能留下多行同名（见 p8_dedup.sql 清理），裸 selectOne 遇多行会抛
     * TooManyResultsException 使上传直接失败，这里取最新一条作为"当前"文档。
     */
    private KbDocument findLatestByFilename(String filename) {
        return kbDocumentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getFilename, filename)
                .orderByDesc(KbDocument::getCreatedAt)
                .last("LIMIT 1"));
    }

    /** 文件名清洗：去掉目录路径，仅保留文件名，避免 MinIO key 出现异常层级 */
    private String sanitizeFilename(String filename) {
        String safe = filename.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1);
        return safe.trim();
    }

    @Override
    public KbDocument uploadTextIfAbsent(String filename, String text) {
        // 评测注入与异步 worker 可能处理同名文件，加锁串行避免互删/重复处理竞态
        return kbFilenameLock.runWithLock(filename, () -> uploadTextIfAbsentLocked(filename, text));
    }

    private KbDocument uploadTextIfAbsentLocked(String filename, String text) {
        KbDocument exist = findLatestByFilename(filename);
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
        doc.setFileHash(sha256Hex(bytes));
        // P9：评测注入文档归默认库（迁移未执行时 kbId=null，评测走 includeEval 全库召回不受影响）
        Long defaultKb = kbService.getDefaultKbId();
        if (defaultKb != null) {
            doc.setKbId(defaultKb);
        }
        kbDocumentMapper.insert(doc);
        // 同样落 MinIO：评测注入的文档也能在知识库「查看原文」
        String objectKey = "kb/" + doc.getId() + "/" + sanitizeFilename(filename);
        try {
            minioStorage.put(objectKey, bytes, "text/markdown");
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
    public void markSource(Long id, String source) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if (source == null || source.isBlank()) {
            return;
        }
        doc.setSource(source);
        kbDocumentMapper.updateById(doc);
    }

    @Override
    public List<KbDocument> list() {
        return list(null);
    }

    @Override
    public List<KbDocument> list(Long kbId) {
        // P8-6c：知识库列表只展示用户上传文档（UPLOAD），评测注入的 EVAL 样例不污染管理界面
        // P9：kbId 非空时限定该库
        return kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSource, "UPLOAD")
                .eq(kbId != null, KbDocument::getKbId, kbId)
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
    public SourceText getSource(Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该文档未保存原始文件（旧数据），请删除后重新上传");
        }
        byte[] bytes = minioStorage.get(doc.getObjectKey());
        try {
            String text = textExtractor.extract(doc.getFilename(), bytes).text();
            int lineCount = text.isEmpty() ? 0 : text.split("\n", -1).length;
            return new SourceText(doc.getFilename(), doc.getContentType(), text, lineCount);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取原文失败: " + friendlyError(e.getMessage()));
        }
    }

    @Override
    public void delete(Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        // P9-5a：文件名锁内删除，等待同文件名在途 worker 结束后再清理（可重入，两阶段替换嵌套调用安全）；
        // 先取消该文档未消费任务（QUEUED/RUNNING→CANCELLED），避免 worker 在删除后又把切片写回
        kbFilenameLock.runWithLock(doc.getFilename(), () -> {
            ingestTaskMapper.cancelQueuedByDocument(id);
            // 删除 MinIO 原始文件（幂等，失败不影响主流程）
            if (doc.getObjectKey() != null && !doc.getObjectKey().isBlank()) {
                minioStorage.delete(doc.getObjectKey());
            }
            cleanupChunksAndVectors(id);
            kbDocumentMapper.deleteById(id);
            // P8-7a：知识库内容变化，失效检索缓存
            retrievalService.invalidateCache();
        });
    }

    @Override
    public void deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要删除的文档");
        }
        for (Long id : ids) {
            delete(id);
        }
    }

    @Override
    public KbDocument enqueueRechunk(Long id, ChunkParams params) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该文档未保存原始文件，无法重新切片");
        }
        // 参数覆盖整体替换：null 字段 = 重置回全局默认
        doc.setChunkMaxChars(params != null ? params.maxChunkChars() : null);
        doc.setChunkOverlapChars(params != null ? params.overlapChars() : null);
        doc.setChunkSemantic(params != null ? params.semantic() : null);
        doc.setStatus("PENDING");
        doc.setErrorMsg(null);
        doc.setChunkCount(0);
        kbDocumentMapper.updateById(doc);
        log.info("重新切片文档已入队: {} (id={})", doc.getFilename(), id);
        enqueue(IngestTask.TYPE_RECHUNK, id);
        return doc;
    }

    /**
     * P9-5a：异步 worker 实际处理。由 {@code ingest_task} 消费线程在文件名锁内调用——
     * UPLOAD：处理成功后删除旧的同名文档（两阶段替换，失败则旧文档原样保留可检索）；
     * RECHUNK/RETRY：先清旧切片与向量再重跑。文档已删除返回 null（worker 标记任务跳过）。
     */
    @Override
    public KbDocument processDocument(Long documentId, String taskType) {
        KbDocument doc = kbDocumentMapper.selectById(documentId);
        if (doc == null) {
            return null;
        }
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该文档未保存原始文件，请删除后重新上传");
        }
        byte[] bytes = minioStorage.get(doc.getObjectKey());

        // UPLOAD 两阶段替换：取"同名且非自身"的最新旧文档，处理成功后删除（worker 在文件名锁内，串行安全）
        KbDocument exist = null;
        if (IngestTask.TYPE_UPLOAD.equals(taskType)) {
            exist = kbDocumentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                    .eq(KbDocument::getFilename, doc.getFilename())
                    .ne(KbDocument::getId, doc.getId())
                    .orderByDesc(KbDocument::getCreatedAt)
                    .last("LIMIT 1"));
        }
        // RECHUNK/RETRY 先清旧切片/向量，避免重跑后数据重复
        if (IngestTask.TYPE_RETRY.equals(taskType) || IngestTask.TYPE_RECHUNK.equals(taskType)) {
            cleanupChunksAndVectors(documentId);
            doc.setChunkCount(0);
        }

        doc.setStatus("PROCESSING");
        kbDocumentMapper.updateById(doc);
        log.info("异步处理文档: {} type={} (id={})", doc.getFilename(), taskType, documentId);

        KbDocument result = process(doc, bytes);
        if (IngestTask.TYPE_UPLOAD.equals(taskType) && exist != null) {
            delete(exist.getId()); // 旧文档删除（含 MinIO/切片/向量/缓存失效）
        }
        return result;
    }

    /** 上传携带的切片参数覆盖写入文档列（仅非 null 字段） */
    private void applyChunkParams(KbDocument doc, ChunkParams params) {
        if (params == null) {
            return;
        }
        doc.setChunkMaxChars(params.maxChunkChars());
        doc.setChunkOverlapChars(params.overlapChars());
        doc.setChunkSemantic(params.semantic());
    }

    /** 解析分片参数：每文档覆盖 > kb_chunk_settings 全局设置 > yml 默认 */
    private ChunkingService.ChunkOptions resolveChunkOptions(KbDocument doc) {
        KbChunkSettings s = chunkSettingsMapper.selectById(1);
        int maxChars = firstNotNull(doc.getChunkMaxChars(),
                s != null ? s.getMaxChunkChars() : null, chunkingProps.getMaxChunkChars());
        int overlap = firstNotNull(doc.getChunkOverlapChars(),
                s != null ? s.getOverlapChars() : null, chunkingProps.getOverlapChars());
        boolean semantic = firstNotNull(doc.getChunkSemantic(),
                s != null ? s.getSemanticEnabled() : null, chunkingProps.isSemanticEnabled());
        return new ChunkingService.ChunkOptions(maxChars, overlap, semantic, doc.getFilename());
    }

    private static <T> T firstNotNull(T a, T b, T c) {
        return a != null ? a : (b != null ? b : c);
    }

    /**
     * 清空某文档的切片与向量（删除 / 重试前清理 / 失败补偿三处共用）。
     * P8-2b：Qdrant 删除失败不再静默忽略——失败说明向量未清掉（孤儿向量仍会被稠密检索命中），
     * 直接抛出让调用方可感知、可重试；失败补偿路径（process catch）外层已有 try/catch 兜底。
     * Qdrant 删除本身幂等（向量/集合不存在也成功）。
     */
    private void cleanupChunksAndVectors(Long documentId) {
        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, documentId));
        List<String> vectorIds = chunks.stream().map(DocumentChunk::getVectorId).toList();
        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
        }
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
    }

    private KbDocument process(KbDocument doc, byte[] bytes) {
        String filename = doc.getFilename();
        try {
            DocumentTextExtractor.ExtractedText extracted = textExtractor.extract(filename, bytes);
            if (extracted.text().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未提取到文本内容");
            }
            // 结构感知分片：按标题分节 + 段落组块 + 句子边界 + 重叠 + 可选语义分片，并带出原文行号/字符范围。
            // 参数按「每文档覆盖 > 全局设置 > yml 默认」解析（见 resolveChunkOptions）。
            List<ChunkingService.Chunk> chunks = chunkingService.chunk(extracted.text(), resolveChunkOptions(doc));

            List<Document> toStore = new ArrayList<>();
            int idx = 0;
            for (ChunkingService.Chunk chunk : chunks) {
                Integer page = extracted.linePages() != null && chunk.startLine() < extracted.linePages().length
                        ? extracted.linePages()[chunk.startLine()]
                        : null;

                Map<String, Object> meta = new HashMap<>();
                // Qdrant payload 不支持 Long，雪花 ID 转 String 存储
                meta.put("documentId", String.valueOf(doc.getId()));
                meta.put("filename", filename);
                // P9：写入选库标记（供未来 dense-filter-by-kbid 开启后做 Qdrant 预过滤；
                // 当前检索靠 DB 后置过滤保证正确性，旧向量无此 payload 也能被后置过滤兼容）
                if (doc.getKbId() != null) {
                    meta.put("kbId", String.valueOf(doc.getKbId()));
                }
                meta.put("chunkIndex", idx);
                meta.put("title", chunk.title());
                // 引用溯源元数据：行号/字符偏移（0 基，半开区间）+ 章节路径 + PDF 页码
                meta.put("headingPath", chunk.headingPath());
                meta.put("lineStart", chunk.startLine());
                meta.put("lineEnd", chunk.endLine());
                meta.put("charStart", chunk.startChar());
                meta.put("charEnd", chunk.endChar());
                if (page != null) {
                    meta.put("page", page);
                }

                Document entry = new Document(chunk.content(), meta);
                DocumentChunk dc = new DocumentChunk();
                dc.setDocumentId(doc.getId());
                dc.setContent(chunk.content());
                dc.setChunkIndex(idx);
                dc.setVectorId(entry.getId());
                dc.setHeadingPath(chunk.headingPath());
                dc.setLineStart(chunk.startLine());
                dc.setLineEnd(chunk.endLine());
                dc.setCharStart(chunk.startChar());
                dc.setCharEnd(chunk.endChar());
                dc.setPage(page);
                documentChunkMapper.insert(dc);

                toStore.add(entry);
                idx++;
            }
            // DashScope 兼容嵌入接口单次最多 10 条文本（超过报 400 batch size invalid）。
            // 切片数往往远超 10，按上限分批向量化入 Qdrant，保证大文档正常入库。
            // 高峰期嵌入接口偶发 429/5xx，复用 AiRetry 指数退避自动重试，避免整篇文档因单批失败作废；
            // 重试耗尽仍失败则抛异常走下方 catch 的失败补偿清理。
            for (int i = 0; i < toStore.size(); i += MAX_EMBED_BATCH) {
                List<Document> batch = toStore.subList(i, Math.min(i + MAX_EMBED_BATCH, toStore.size()));
                AiRetry.callWithRetry(() -> {
                    vectorStore.add(batch);
                    return null;
                });
            }

            doc.setChunkCount(toStore.size());
            doc.setStatus("READY");
            doc.setErrorMsg(null);
            kbDocumentMapper.updateById(doc);
            // P8-7a：知识库内容变化，失效检索缓存，避免命中过期切片
            retrievalService.invalidateCache();
            log.info("知识库文档已入库: {} ({} 切片)", filename, toStore.size());
            return doc;
        } catch (Exception e) {
            log.error("知识库文档处理失败: {}", filename, e);
            // 失败补偿清理：删掉本次失败前已写入的切片与已入 Qdrant 的向量，
            // 避免 FAILED 文档的半成品仍被问答检索到（检索两端都不按 status 过滤）。
            // 清理本身失败只告警，不掩盖原始错误。
            try {
                cleanupChunksAndVectors(doc.getId());
            } catch (Exception cleanupEx) {
                log.warn("失败补偿清理切片/向量失败: {}", cleanupEx.getMessage());
            }
            // P9-5a：保留原始错误码——BAD_REQUEST(400) 是永久失败（如扫描件无文本），worker 据此直接进 DLQ
            // 不盲目重试；SYSTEM_ERROR(500) 视为瞬时可重试。errorMsg 供前端 FAILED 徽章展示。
            String msg = friendlyError(e.getMessage());
            doc.setStatus("FAILED");
            doc.setErrorMsg(msg);
            kbDocumentMapper.updateById(doc);
            int code = e instanceof BusinessException be
                    ? be.getCode() : ErrorCode.SYSTEM_ERROR.getCode();
            throw new BusinessException(code, "文档处理失败: " + msg);
        }
    }

    /** SHA-256 十六进制摘要（内容哈希，用于判断同名文件是否真的变化） */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

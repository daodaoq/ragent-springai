package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.service.KnowledgeBaseService;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.result.PageResult;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库管理接口
 */
@RestController
@RequestMapping("/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckLogin
    public Result<KbDocument> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Integer maxChunkChars,
            @RequestParam(required = false) Integer overlapChars,
            @RequestParam(required = false) Boolean semantic) {
        return Result.success(kbService.upload(file,
                new KnowledgeBaseService.ChunkParams(maxChunkChars, overlapChars, semantic)));
    }

    /**
     * 批量上传：一次提交多个文件，后端线程池并行处理，逐文件返回结果。
     * configs 为可选 JSON 数组 [{filename, maxChunkChars?, overlapChars?, semantic?}]，按 filename 对齐各文件参数。
     */
    @PostMapping(value = "/documents/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckLogin
    public Result<List<KnowledgeBaseService.UploadResult>> uploadBatch(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "configs", required = false) String configs) {
        return Result.success(kbService.uploadBatch(files, parseBatchConfigs(files, configs)));
    }

    /** 重新切片：按新参数覆盖重新切分+向量化（先清旧切片/向量），null 字段 = 重置回全局默认 */
    @PostMapping("/documents/{id}/rechunk")
    @SaCheckRole(value = {"ADMIN", "TEACHER"}, mode = SaMode.OR)
    public Result<KbDocument> rechunk(@PathVariable Long id,
                                      @RequestBody(required = false) KnowledgeBaseService.ChunkParams params) {
        return Result.success(kbService.rechunk(id, params));
    }

    /** 解析批量上传的 configs JSON（[{filename,...}]）→ 与 files 按 filename 对齐的 ChunkParams 列表 */
    private List<KnowledgeBaseService.ChunkParams> parseBatchConfigs(List<MultipartFile> files, String configs) {
        if (configs == null || configs.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<UploadConfig> list = objectMapper.readValue(configs, new TypeReference<>() {});
            Map<String, KnowledgeBaseService.ChunkParams> byName = new HashMap<>();
            for (UploadConfig c : list) {
                if (c.filename() != null) {
                    byName.put(c.filename(),
                            new KnowledgeBaseService.ChunkParams(c.maxChunkChars(), c.overlapChars(), c.semantic()));
                }
            }
            return files.stream().map(f -> byName.get(f.getOriginalFilename())).collect(Collectors.toList());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "切片参数 configs 格式错误");
        }
    }

    /** 批量上传 configs JSON 里的单条记录（按 filename 与文件对齐） */
    private record UploadConfig(String filename, Integer maxChunkChars, Integer overlapChars, Boolean semantic) {
    }

    /** 文档列表（P8-8c：改为登录可见，避免匿名探测知识库文件名/状态） */
    @GetMapping("/documents")
    @SaCheckLogin
    public Result<List<KbDocument>> list() {
        return Result.success(kbService.list());
    }

    /** 重试处理失败的文档：从 MinIO 读原始文件重新切分+向量化，无需重新上传 */
    @PostMapping("/documents/{id}/retry")
    @SaCheckRole(value = {"ADMIN", "TEACHER"}, mode = SaMode.OR)
    public Result<KbDocument> retry(@PathVariable Long id) {
        return Result.success(kbService.retry(id));
    }

    @DeleteMapping("/documents/{id}")
    @SaCheckRole(value = {"ADMIN", "TEACHER"}, mode = SaMode.OR)
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.success();
    }

    /** 批量删除：一次删除多个文档（含切片、向量、MinIO 原始文件） */
    @PostMapping("/documents/batch-delete")
    @SaCheckRole(value = {"ADMIN", "TEACHER"}, mode = SaMode.OR)
    public Result<Void> deleteBatch(@RequestBody List<Long> ids) {
        kbService.deleteBatch(ids);
        return Result.success();
    }

    /** 文档切片分页查看（按 chunk_index 升序） */
    @GetMapping("/documents/{id}/chunks")
    @SaCheckLogin
    public Result<PageResult<DocumentChunk>> chunks(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(kbService.chunks(id, page, pageSize));
    }

    /** 查看原文：从 MinIO 读取原始文件并提取文本，供知识库「查看原文」与聊天来源跳转 */
    @GetMapping("/documents/{id}/source")
    @SaCheckLogin
    public Result<KnowledgeBaseService.SourceText> source(@PathVariable Long id) {
        return Result.success(kbService.getSource(id));
    }
}

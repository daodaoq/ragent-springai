package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.common.result.PageResult;
import com.ragent.common.result.Result;
import com.ragent.ai.entity.DocumentChunk;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.service.KnowledgeBaseService;
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

import java.util.List;

/**
 * 知识库管理接口
 */
@RestController
@RequestMapping("/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckLogin
    public Result<KbDocument> upload(@RequestPart("file") MultipartFile file) {
        return Result.success(kbService.upload(file));
    }

    /** 批量上传：一次提交多个文件，后端线程池并行处理，逐文件返回结果 */
    @PostMapping(value = "/documents/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckLogin
    public Result<List<KnowledgeBaseService.UploadResult>> uploadBatch(
            @RequestPart("files") List<MultipartFile> files) {
        return Result.success(kbService.uploadBatch(files));
    }

    @GetMapping("/documents")
    public Result<List<KbDocument>> list() {
        return Result.success(kbService.list());
    }

    /** 重试处理失败的文档：从 MinIO 读原始文件重新切分+向量化，无需重新上传 */
    @PostMapping("/documents/{id}/retry")
    @SaCheckLogin
    public Result<KbDocument> retry(@PathVariable Long id) {
        return Result.success(kbService.retry(id));
    }

    @DeleteMapping("/documents/{id}")
    @SaCheckLogin
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.success();
    }

    /** 批量删除：一次删除多个文档（含切片、向量、MinIO 原始文件） */
    @PostMapping("/documents/batch-delete")
    @SaCheckLogin
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

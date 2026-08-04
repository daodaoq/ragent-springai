package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.common.result.Result;
import com.ragent.web.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文件上传接口（MinIO）。仅登录用户可上传；类型/大小校验在 FileService 中完成。
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 上传文件，返回 objectName（存入 DB）+ pre-signed URL（前端预览）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckLogin
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        String objectName = fileService.upload(file);
        String url = fileService.getUrl(objectName);
        return Result.success(Map.of("objectName", objectName, "url", url));
    }
}

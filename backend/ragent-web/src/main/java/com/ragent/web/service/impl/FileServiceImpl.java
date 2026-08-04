package com.ragent.web.service.impl;

import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.web.config.MinioConfig;
import com.ragent.web.service.FileService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * MinIO 文件存储实现。
 * <p>
 * 文件按日期分目录，以 UUID 重命名防止冲突；上传前校验类型与大小；
 * 上传后返回预签名直链 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 仅允许图片类扩展名，防止上传 HTML/SVG 等造成存储型 XSS 或污染桶 */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".ico");

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @Override
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能超过 5MB");
        }
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase(Locale.ROOT);
        }
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持图片文件（jpg/png/gif/webp/bmp/ico）");
        }

        // 按日期分目录：yyyy/MM/dd/uuid.ext
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectName = datePath + "/" + UUID.randomUUID() + ext;

        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            log.info("文件上传成功: bucket={}, object={}, size={}", minioConfig.getBucket(), objectName, file.getSize());
            return objectName;
        } catch (Exception e) {
            log.error("文件上传失败: {}", objectName, e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @Override
    public String getUrl(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return null;
        }
        // 如果是完整 http/https URL 则直接返回（兼容旧数据）
        if (objectName.startsWith("http://") || objectName.startsWith("https://")) {
            return objectName;
        }
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .method(Method.GET)
                            .expiry(minioConfig.getUrlExpiry())
                            .build()
            );
        } catch (Exception e) {
            log.error("获取文件 URL 失败: {}", objectName, e);
            return null;
        }
    }

    @Override
    public void delete(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        // 跳过外部 URL
        if (objectName.startsWith("http://") || objectName.startsWith("https://")) {
            return;
        }
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .build()
            );
            log.info("文件删除成功: bucket={}, object={}", minioConfig.getBucket(), objectName);
        } catch (Exception e) {
            log.error("文件删除失败: {}", objectName, e);
            // 不抛异常，删除失败不影响主流程
        }
    }

    @Override
    public boolean exists(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return false;
        }
        // 外部 URL 无法校验，视为存在
        if (objectName.startsWith("http://") || objectName.startsWith("https://")) {
            return true;
        }
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

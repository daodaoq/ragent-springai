package com.ragent.common.storage;

import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储通用服务：上传 / 下载 / 直链 / 删除 / 存在性判断。
 * <p>放在 common 供各模块复用：头像（ragent-web）、知识库原始文件（ragent-ai）等。
 * object key 由调用方自定义前缀隔离，如 {@code kb/{docId}/{filename}}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    /** 上传字节流到指定 object key，返回该 key */
    public String put(String objectKey, byte[] bytes, String contentType) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "存储路径不能为空");
        }
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            log.info("MinIO 上传成功: {}/{} ({} bytes)", minioConfig.getBucket(), objectKey, bytes.length);
            return objectKey;
        } catch (Exception e) {
            log.error("MinIO 上传失败: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件存储失败: " + e.getMessage());
        }
    }

    /** 下载对象内容为字节数组 */
    public byte[] get(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "存储路径不能为空");
        }
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioConfig.getBucket())
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.error("MinIO 下载失败: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取存储文件失败: " + e.getMessage());
        }
    }

    /** 获取预签名访问 URL（带有效期）；外部 http(s) URL 原样返回 */
    public String getUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        if (objectKey.startsWith("http://") || objectKey.startsWith("https://")) {
            return objectKey;
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectKey)
                    .method(Method.GET)
                    .expiry(minioConfig.getUrlExpiry())
                    .build());
        } catch (Exception e) {
            log.error("MinIO 获取 URL 失败: {}", objectKey, e);
            return null;
        }
    }

    /** 删除对象（幂等，失败仅告警不影响主流程） */
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if (objectKey.startsWith("http://") || objectKey.startsWith("https://")) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectKey)
                    .build());
            log.info("MinIO 删除成功: {}", objectKey);
        } catch (Exception e) {
            log.warn("MinIO 删除失败（忽略）: {}", objectKey);
        }
    }

    /** 判断对象是否存在（外部 http(s) URL 无法校验，视为存在） */
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        if (objectKey.startsWith("http://") || objectKey.startsWith("https://")) {
            return true;
        }
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

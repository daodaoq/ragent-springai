package com.ragent.web.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置。
 * <p>
 * 初始化 MinioClient bean，并在应用启动时确保 bucket 存在；
 * 配置项从 application.yml 的 minio 前缀读取。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private int urlExpiry = 604800;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /**
     * 启动时确保 bucket 存在（自动创建），避免首次上传报 NoSuchBucket。
     * MinIO 未启动/不可用时仅告警，不阻断应用启动。
     */
    @Bean
    public ApplicationRunner minioBucketInit(MinioClient minioClient) {
        return args -> {
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("MinIO bucket 已自动创建: {}", bucket);
                }
            } catch (Exception e) {
                log.warn("MinIO 不可用或 bucket 初始化失败（首次上传前请确认 MinIO 已启动并配置正确）: {}", e.getMessage());
            }
        };
    }
}

package com.ragent.web.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务（MinIO）。
 */
public interface FileService {

    /**
     * 上传文件到 MinIO，返回访问 URL。
     */
    String upload(MultipartFile file);

    /**
     * 获取文件访问 URL（带有效期）。
     */
    String getUrl(String objectName);

    /**
     * 删除文件。
     */
    void delete(String objectName);

    /**
     * 判断对象是否存在（外部 http(s) URL 视为存在）。
     */
    boolean exists(String objectName);
}

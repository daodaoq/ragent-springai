package com.ragent.web.service.impl;

import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.storage.MinioStorageService;
import com.ragent.web.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 头像文件服务（MinIO）。
 * <p>
 * 实际对象存储操作委托给 common 的 {@link MinioStorageService}，这里只保留
 * 头像特有的校验（类型白名单 + 大小限制）和 key 生成规则（按日期分目录 + UUID 重命名）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 仅允许图片类扩展名，防止上传 HTML/SVG 等造成存储型 XSS 或污染桶 */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".ico");

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final MinioStorageService minioStorage;

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
            return minioStorage.put(objectName, file.getBytes(), file.getContentType());
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }
    }

    @Override
    public String getUrl(String objectName) {
        return minioStorage.getUrl(objectName);
    }

    @Override
    public void delete(String objectName) {
        minioStorage.delete(objectName);
    }

    @Override
    public boolean exists(String objectName) {
        return minioStorage.exists(objectName);
    }
}

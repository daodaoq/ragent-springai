package com.ragent.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分片配置（application.yml 中 ragent.chunking.*）
 */
@Data
@ConfigurationProperties(prefix = "ragent.chunking")
public class ChunkingProperties {

    /** 单个切片最大字符数（中文约 300-400 token） */
    private int maxChunkChars = 800;

    /** 相邻切片重叠字符数，避免切点上下文丢失 */
    private int overlapChars = 100;
}

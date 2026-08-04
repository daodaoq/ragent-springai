package com.ragent.ai.config;

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

    /** 语义分片开关：长且无标题的小节按段落 embedding 相似度断片（默认关闭） */
    private boolean semanticEnabled = false;

    /** 相邻段落余弦相似度低于该值视为主题切换，强制断片 */
    private double semanticThreshold = 0.5;

    /** 参与语义分片的最小块数（块太少时语义分片无意义） */
    private int semanticMinBlocks = 3;
}

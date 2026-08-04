package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库全局切片参数（单行，id 固定为 1）。
 * 前端「切片质量」页可改；kb_document 的每文档覆盖列优先级更高。
 */
@Data
@TableName("kb_chunk_settings")
public class KbChunkSettings {

    @TableId(type = IdType.INPUT)
    private Integer id;

    /** 单切片最大字符数 */
    private Integer maxChunkChars;

    /** 重叠字符数 */
    private Integer overlapChars;

    /** 语义分片开关 */
    private Boolean semanticEnabled;

    /** 更新时间（DB 端 ON UPDATE CURRENT_TIMESTAMP 维护） */
    private LocalDateTime updatedAt;
}

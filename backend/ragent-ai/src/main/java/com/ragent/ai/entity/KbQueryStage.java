package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 查询处理管线阶段配置（每行一个阶段，DB 是运行时真相）。
 * 前端「切片质量」页可勾选启用/排序；QueryPipeline 按 sort_order 遍历启用阶段执行。
 */
@Data
@TableName("kb_query_stage")
public class KbQueryStage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 阶段名：context/normalize/intent/rewrite/multiQuery/hyde/entity */
    private String name;

    /** 启用：0否 1是 */
    private Boolean enabled;

    /** 执行顺序（越小越先） */
    private Integer sortOrder;

    /** 更新时间（DB 端 ON UPDATE CURRENT_TIMESTAMP 维护） */
    private LocalDateTime updatedAt;
}

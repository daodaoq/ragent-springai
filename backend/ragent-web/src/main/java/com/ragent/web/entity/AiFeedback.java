package com.ragent.web.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 回答反馈表（赞/踩，append-only，无逻辑删除）
 */
@Data
@TableName("ai_feedback")
public class AiFeedback {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 评价用户（可能未登录） */
    private Long userId;

    /** P8-6a：原回答的全链路 traceId（可关联查询日志/ELK 定位坏案例） */
    private String traceId;

    private String conversationId;

    private String question;

    private String answer;

    /** 1 赞 / -1 踩 */
    private Integer rating;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

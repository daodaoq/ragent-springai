package com.ragent.web.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题表
 */
@Data
@TableName("question")
public class Question {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;

    /** 内容(Markdown) */
    private String content;

    private Long userId;

    /** OPEN / RESOLVED */
    private String status;

    private Long bestAnswerId;

    private Integer viewCount;

    private Integer answerCount;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

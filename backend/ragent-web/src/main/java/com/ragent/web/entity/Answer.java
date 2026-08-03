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
 * 回答表
 */
@Data
@TableName("answer")
public class Answer {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long questionId;

    private Long userId;

    /** 内容(Markdown) */
    private String content;

    /** 是否被采纳: 0否 1是 */
    private Integer isAccepted;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库（P9 多知识库：共享池 + 分级管理）。
 * 所有登录用户可见全部库并都可检索；仅 TEACHER/ADMIN 创建/管理；学生只读。
 * owner_id 仅作记录（展示创建人），不参与检索过滤（共享池模型无成员表）。
 */
@Data
@TableName("kb")
public class Kb {

    /** 自增主键；id=1 保留为系统默认库（历史文档/评测文档归此，不可删除） */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 创建人 sys_user.id（仅记录用） */
    private Long ownerId;

    /** 默认库标记（1=默认，不可删除；历史/评测文档归此） */
    private Boolean isDefault;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

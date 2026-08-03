package com.ragent.web.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 问题-标签关联表
 */
@Data
@TableName("question_tag")
public class QuestionTag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long questionId;

    private Long tagId;
}

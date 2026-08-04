package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档切片
 */
@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long documentId;

    private String content;

    private Integer chunkIndex;

    /** Qdrant point id */
    private String vectorId;

    /** 章节路径（如 "# 第一章 > ## 1.1"） */
    private String headingPath;

    /** 切片在原始文件中的起始/结束行号（0 基，指向正文不含标题前缀） */
    private Integer lineStart;

    private Integer lineEnd;

    /** 切片在原始文件中的起始/结束字符偏移（0 基，半开区间） */
    private Integer charStart;

    private Integer charEnd;

    /** PDF 页码（1 基；非 PDF 为 null） */
    private Integer page;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

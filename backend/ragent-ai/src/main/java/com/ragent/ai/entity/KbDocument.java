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
 * 知识库文档
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** P9：所属知识库 ID（kb.id；null = 未迁移/未归属，检索按"全部库"处理） */
    private Long kbId;

    private String filename;

    private String contentType;

    private Integer size;

    /** MinIO 原始文件 object key（上传时先落 MinIO，失败可据此重试，无需重新上传） */
    private String objectKey;

    /** 原文件内容 SHA-256（十六进制），用于判断同名文件是否真的变化 */
    private String fileHash;

    /** 切片参数覆盖：单切片最大字符数（null = 用全局默认） */
    private Integer chunkMaxChars;

    /** 切片参数覆盖：重叠字符数（null = 用全局默认） */
    private Integer chunkOverlapChars;

    /** 切片参数覆盖：语义分片开关（null = 用全局默认） */
    private Boolean chunkSemantic;

    private Integer chunkCount;

    /** PENDING / PROCESSING / READY / FAILED */
    private String status;

    /** P8-6c：文档来源——UPLOAD(用户上传)/EVAL(评测注入)；生产检索只召回 UPLOAD，避免评测样例污染真实 KB */
    private String source;

    /** P9-5a：最近一次处理失败原因（异步 worker 写 DLQ / 失败补偿时回填，前端 FAILED 徽章展示） */
    private String errorMsg;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

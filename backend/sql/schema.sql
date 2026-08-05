-- ============================================
-- 人工智能实验室问答系统 - P1 建表
-- MySQL 8.4, utf8mb4
-- ============================================

-- 注意: 表名用 sys_user 而非 user（user 是 MySQL 保留字，裸查询会报错）
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id`          BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `username`    VARCHAR(50)  NOT NULL COMMENT '登录名',
  `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码',
  `nickname`    VARCHAR(50)  NOT NULL COMMENT '昵称',
  `role`        VARCHAR(20)  NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT/TEACHER/ADMIN',
  `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `bio`         VARCHAR(255) DEFAULT NULL COMMENT '个人简介',
  `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0否 1是',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `question` (
  `id`              BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `title`           VARCHAR(200) NOT NULL COMMENT '标题',
  `content`         MEDIUMTEXT   NOT NULL COMMENT '内容(Markdown)',
  `user_id`         BIGINT       NOT NULL COMMENT '提问人',
  `status`          VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/RESOLVED',
  `best_answer_id`  BIGINT       DEFAULT NULL COMMENT '采纳的回答ID',
  `view_count`      INT          NOT NULL DEFAULT 0 COMMENT '浏览数',
  `answer_count`    INT          NOT NULL DEFAULT 0 COMMENT '回答数',
  `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0否 1是',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问题表';

CREATE TABLE IF NOT EXISTS `answer` (
  `id`            BIGINT     NOT NULL COMMENT '主键(雪花ID)',
  `question_id`   BIGINT     NOT NULL COMMENT '所属问题',
  `user_id`       BIGINT     NOT NULL COMMENT '回答人',
  `content`       MEDIUMTEXT NOT NULL COMMENT '内容(Markdown)',
  `is_accepted`   TINYINT    NOT NULL DEFAULT 0 COMMENT '是否被采纳: 0否 1是',
  `deleted`       TINYINT    NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0否 1是',
  `created_at`    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_question_id` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回答表';

CREATE TABLE IF NOT EXISTS `tag` (
  `id`          BIGINT      NOT NULL COMMENT '主键(雪花ID)',
  `name`        VARCHAR(50) NOT NULL COMMENT '标签名',
  `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0否 1是',
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签表';

CREATE TABLE IF NOT EXISTS `question_tag` (
  `id`            BIGINT NOT NULL COMMENT '主键(雪花ID)',
  `question_id`   BIGINT NOT NULL COMMENT '问题ID',
  `tag_id`        BIGINT NOT NULL COMMENT '标签ID',
  PRIMARY KEY (`id`),
  KEY `idx_question_id` (`question_id`),
  KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问题-标签关联表';

-- ============================================
-- P3 知识库表
-- ============================================
CREATE TABLE IF NOT EXISTS `kb_document` (
  `id`           BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `filename`     VARCHAR(255) NOT NULL COMMENT '文件名',
  `content_type` VARCHAR(100) DEFAULT NULL COMMENT '文件类型',
  `size`         INT          NOT NULL DEFAULT 0 COMMENT '字节数',
  `object_key`   VARCHAR(255) DEFAULT NULL COMMENT 'MinIO 原始文件 key（失败可据此重试）',
  `file_hash`    VARCHAR(64)  DEFAULT NULL COMMENT '原文件内容 SHA-256（内容去重判断）',
  `chunk_max_chars`    INT   DEFAULT NULL COMMENT '切片参数覆盖: 单切片最大字符数(NULL=全局默认)',
  `chunk_overlap_chars` INT  DEFAULT NULL COMMENT '切片参数覆盖: 重叠字符数(NULL=全局默认)',
  `chunk_semantic`      TINYINT DEFAULT NULL COMMENT '切片参数覆盖: 语义分片(NULL=全局默认)',
  `chunk_count`  INT          NOT NULL DEFAULT 0 COMMENT '切片数',
  `status`       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/READY/FAILED',
  `source`       VARCHAR(20)  NOT NULL DEFAULT 'UPLOAD' COMMENT '文档来源: UPLOAD/EVAL(评测注入，生产检索排除)',
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- 全局切片参数设置（单行；前端可改，per-doc 覆盖优先级更高）
CREATE TABLE IF NOT EXISTS `kb_chunk_settings` (
  `id`              TINYINT   NOT NULL DEFAULT 1 COMMENT '固定单行',
  `max_chunk_chars` INT       NOT NULL DEFAULT 800 COMMENT '单切片最大字符数',
  `overlap_chars`   INT       NOT NULL DEFAULT 100 COMMENT '重叠字符数',
  `semantic_enabled` TINYINT  NOT NULL DEFAULT 0 COMMENT '语义分片开关',
  `updated_at`      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库全局切片参数';

CREATE TABLE IF NOT EXISTS `document_chunk` (
  `id`           BIGINT      NOT NULL COMMENT '主键(雪花ID)',
  `document_id`  BIGINT      NOT NULL COMMENT '所属文档',
  `content`      MEDIUMTEXT  NOT NULL COMMENT '切片文本',
  `chunk_index`  INT         NOT NULL DEFAULT 0 COMMENT '切片序号',
  `vector_id`    VARCHAR(64) DEFAULT NULL COMMENT 'Qdrant point id',
  `heading_path` VARCHAR(255) DEFAULT NULL COMMENT '章节路径（如 "# 第一章 > ## 1.1"）',
  `line_start`   INT         DEFAULT NULL COMMENT '起始行号（0 基，指向正文）',
  `line_end`     INT         DEFAULT NULL COMMENT '结束行号（0 基，指向正文）',
  `char_start`   INT         DEFAULT NULL COMMENT '起始字符偏移（0 基）',
  `char_end`     INT         DEFAULT NULL COMMENT '结束字符偏移（0 基，开区间）',
  `page`         INT         DEFAULT NULL COMMENT 'PDF 页码（1 基；非 PDF 为 NULL）',
  `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_document_id` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库切片表';

-- ============================================
-- P5: AI 回答反馈表（赞/踩）
-- ============================================
CREATE TABLE IF NOT EXISTS `ai_feedback` (
  `id`              BIGINT      NOT NULL COMMENT '主键(雪花ID)',
  `user_id`         BIGINT      DEFAULT NULL COMMENT '评价用户(可能未登录)',
  `trace_id`        VARCHAR(64) DEFAULT NULL COMMENT '全链路traceId(可关联查询日志/ELK定位坏案例)',
  `conversation_id` VARCHAR(64) DEFAULT NULL COMMENT '会话ID',
  `question`        TEXT        COMMENT '用户问题',
  `answer`          MEDIUMTEXT  COMMENT 'AI 回答',
  `rating`          TINYINT     NOT NULL COMMENT '1 赞 / -1 踩',
  `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 回答反馈表';

-- ============================================
-- P6: 查询处理管线阶段配置（前端编排页可勾选/排序）
-- ============================================
CREATE TABLE IF NOT EXISTS `kb_query_stage` (
  `id`         BIGINT      NOT NULL COMMENT '主键(雪花ID)',
  `name`       VARCHAR(50) NOT NULL COMMENT '阶段名: context/normalize/intent/rewrite/multiQuery/hyde/entity',
  `enabled`    TINYINT     NOT NULL DEFAULT 1 COMMENT '启用: 0否 1是',
  `sort_order` INT         NOT NULL DEFAULT 0 COMMENT '执行顺序(越小越先)',
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询处理管线阶段配置';

-- ============================================
-- P6: RAG 查询日志（自动采集真实查询轨迹，供评测/质量分析）
-- ============================================
CREATE TABLE IF NOT EXISTS `rag_query_log` (
  `id`              BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `user_id`         BIGINT       DEFAULT NULL COMMENT '登录用户ID(未登录为NULL)',
  `trace_id`        VARCHAR(64)  DEFAULT NULL COMMENT '全链路traceId(与ELK请求日志关联)',
  `conversation_id` VARCHAR(64)  DEFAULT NULL COMMENT '会话ID',
  `question`        TEXT         NOT NULL COMMENT '原始问题',
  `intent`          VARCHAR(20)  DEFAULT NULL COMMENT '意图: RAG/CHAT/OTHER',
  `rewritten_query` TEXT         DEFAULT NULL COMMENT '改写后检索查询',
  `gated`           TINYINT      NOT NULL DEFAULT 0 COMMENT '意图门禁拦截: 0否 1是',
  `sources`         MEDIUMTEXT   DEFAULT NULL COMMENT '召回来源JSON(含filename/documentId/score)',
  `answer`          MEDIUMTEXT   DEFAULT NULL COMMENT 'AI回答',
  `latency_ms`      INT          DEFAULT NULL COMMENT '总耗时ms',
  `error`           VARCHAR(500) DEFAULT NULL COMMENT '异常信息(若失败)',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 查询日志';

-- 注意: 初始管理员账号见 sql/seed_admin.sql（一次性手动执行）

-- ============================================
-- P7: AI 会话记忆摘要（滑动窗口溢出时 LLM 压缩，MySQL 持久化）
--      Redis TTL 7 天窗口过期后，仍可从本表恢复长对话上下文
-- ============================================
CREATE TABLE IF NOT EXISTS `ai_conversation_summary` (
  `id`              BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `conversation_id` VARCHAR(64)  NOT NULL COMMENT '会话ID(前端 convId)',
  `summary`         MEDIUMTEXT   NOT NULL COMMENT 'LLM 压缩后的对话摘要',
  `message_count`   INT          NOT NULL DEFAULT 0 COMMENT '已压缩的原始消息条数',
  `last_summary_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次摘要生成时间',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation` (`conversation_id`),
  KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 会话记忆摘要';

-- ============================================
-- P8-6b: RAG 评测结果（历史对比/回归追踪）
-- ============================================
CREATE TABLE IF NOT EXISTS `eval_result` (
  `id`                BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `processed`         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否走查询处理管线(0=原样检索基线)',
  `with_answer`       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否含回答生成+LLM裁判打分(0=仅检索指标)',
  `total_cases`       INT          NOT NULL COMMENT '用例数',
  `recall`            DOUBLE       DEFAULT NULL COMMENT 'Recall@5',
  `precision`         DOUBLE       DEFAULT NULL COMMENT 'Precision@5',
  `mrr`               DOUBLE       DEFAULT NULL COMMENT 'MRR@5',
  `ndcg`              DOUBLE       DEFAULT NULL COMMENT 'NDCG@5',
  `avg_faithfulness`  DOUBLE       DEFAULT NULL COMMENT '平均忠实度',
  `avg_relevance`     DOUBLE       DEFAULT NULL COMMENT '平均相关度',
  `citation_rate`     DOUBLE       DEFAULT NULL COMMENT '引用率',
  `detail_json`       MEDIUMTEXT   DEFAULT NULL COMMENT '完整评测报告JSON(含逐用例)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评测结果';

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
  `content_type` VARCHAR(50)  DEFAULT NULL COMMENT '文件类型',
  `size`         INT          NOT NULL DEFAULT 0 COMMENT '字节数',
  `object_key`   VARCHAR(255) DEFAULT NULL COMMENT 'MinIO 原始文件 key（失败可据此重试）',
  `file_hash`    VARCHAR(64)  DEFAULT NULL COMMENT '原文件内容 SHA-256（内容去重判断）',
  `chunk_count`  INT          NOT NULL DEFAULT 0 COMMENT '切片数',
  `status`       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/READY/FAILED',
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

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
  `conversation_id` VARCHAR(64) DEFAULT NULL COMMENT '会话ID',
  `question`        TEXT        COMMENT '用户问题',
  `answer`          MEDIUMTEXT  COMMENT 'AI 回答',
  `rating`          TINYINT     NOT NULL COMMENT '1 赞 / -1 踩',
  `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 回答反馈表';

-- 注意: 初始管理员账号见 sql/seed_admin.sql（一次性手动执行）

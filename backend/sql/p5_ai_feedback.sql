-- ============================================
-- P5: AI 回答反馈表（赞/踩）
-- 一次性手动执行（勿重复）；同一 DDL 已追加进 schema.sql
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

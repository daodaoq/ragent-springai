-- ============================================
-- P9: 文档摄入异步化——任务队列 + 重试 + DLQ
--
-- 背景：P1-5a。原 upload/rechunk/retry 全同步、批量上传 future.get() 阻塞 HTTP 线程；
-- 大文档 + 嵌入调用可让单次上传拖几十秒。现改为：上传仅做校验+落 MinIO+入队（秒回），
-- 文本抽取/切分/向量化由 ingest_task 队列的轮询 worker 异步消费，失败自动重试（瞬时）或进 DLQ（永久）。
--
-- 幂等：已执行过则重复执行无副作用（CREATE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS）。
-- 已存在的库需手动执行一次；新装见 schema.sql（已并入）。
-- ============================================

CREATE TABLE IF NOT EXISTS `ingest_task` (
  `id`           BIGINT        NOT NULL COMMENT '主键(雪花ID)',
  `document_id`  BIGINT        NOT NULL COMMENT '关联 kb_document.id',
  `task_type`    VARCHAR(20)   NOT NULL COMMENT '任务类型: UPLOAD/RECHUNK/RETRY',
  `status`       VARCHAR(20)   NOT NULL DEFAULT 'QUEUED' COMMENT '状态: QUEUED/RUNNING/SUCCESS/FAILED/DLQ/CANCELLED',
  `attempt`      INT           NOT NULL DEFAULT 0 COMMENT '已尝试次数',
  `max_attempts` INT           NOT NULL DEFAULT 3 COMMENT '最大尝试次数(超限进 DLQ)',
  `last_error`   VARCHAR(1000) DEFAULT NULL COMMENT '最近一次失败原因',
  `trace_id`     VARCHAR(64)   DEFAULT NULL COMMENT '入队时请求 traceId(worker 恢复 MDC 用)',
  `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_id` (`status`, `id`),
  KEY `idx_document_id` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库异步处理任务表';

-- kb_document 补失败原因（前端 FAILED 徽章可展示；worker 写 DLQ 时回填）
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `error_msg` VARCHAR(1000) DEFAULT NULL COMMENT '最近一次处理失败原因' AFTER `source`;

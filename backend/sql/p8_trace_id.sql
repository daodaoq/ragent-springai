-- ============================================
-- P8-7b / P8-6a: 为查询日志与反馈表补充 trace_id 列（全链路追踪断点修复）
-- 对已存在的库执行一次（勿重复；重复执行会因列已存在报错，无副作用）
-- ============================================

ALTER TABLE `rag_query_log` ADD COLUMN `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '全链路traceId(与ELK请求日志关联)' AFTER `user_id`;

ALTER TABLE `ai_feedback` ADD COLUMN `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '全链路traceId(可关联查询日志/ELK定位坏案例)' AFTER `user_id`;

-- ============================================
-- P8-6c: kb_document 新增 source 列（文档来源：UPLOAD 用户上传 / EVAL 评测注入）
-- 生产检索只召回 UPLOAD 文档，评测样例不再污染真实知识库
-- 对已存在的库执行一次（重复执行报列已存在，无副作用）
-- ============================================

ALTER TABLE `kb_document` ADD COLUMN `source` VARCHAR(20) NOT NULL DEFAULT 'UPLOAD' COMMENT '文档来源: UPLOAD/EVAL' AFTER `status`;

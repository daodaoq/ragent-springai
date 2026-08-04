-- ============================================================
-- P4 检索优化：document_chunk 全文索引（ngram 中文分词）
-- 手动执行一次（项目约定：不用启动时自动建库建表）：
--   docker exec -i ragent-mysql mysql -uroot -proot ragent < backend/sql/p4_retrieval.sql
-- MySQL 8 内置 ngram 插件，无需额外安装。
-- 注意：MySQL 不支持 ADD INDEX IF NOT EXISTS，重复执行会报错，只跑一次。
-- ============================================================

USE ragent;

ALTER TABLE `document_chunk`
  ADD FULLTEXT INDEX `ft_content_ngram` (`content`) WITH PARSER ngram;

-- 验证（可选）：
-- SHOW INDEX FROM document_chunk WHERE Index_type = 'FULLTEXT';

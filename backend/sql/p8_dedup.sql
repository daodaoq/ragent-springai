-- ============================================
-- P8-8e: 同名重复文档清理（历史并发批量上传遗留的数据完整性问题）
--
-- ⚠️ 本脚本会修改数据，执行前务必先备份：
--   docker exec ragent-mysql mysqldump -uroot -proot ragent > backups/ragent_before_dedup.sql
--
-- 规则：每组文件名保留「最新一条未逻辑删除」的文档，其余置 deleted=1 并物理删除其切片。
-- 效果：知识库列表/检索不再出现重复；Qdrant 中对应的孤儿向量不随 SQL 删除，
--       但检索侧已按 documentId 过滤 deleted/status，重复文档的向量不会再被召回。
--
-- 幂等：可重复执行（已 deleted=1 的行不参与 group by 的 deleted=0 条件）。
-- ============================================

-- 1. 每组文件名应保留的最新 id
CREATE TEMPORARY TABLE tmp_keep AS
SELECT MAX(id) AS keep_id
FROM kb_document
WHERE deleted = 0
GROUP BY filename;

-- 2. 逻辑删除重复文档（@TableLogic 使应用层自动排除 deleted=1）
UPDATE kb_document d
LEFT JOIN tmp_keep k ON k.keep_id = d.id
SET d.deleted = 1
WHERE d.deleted = 0 AND k.keep_id IS NULL;

-- 3. 物理删除重复文档的切片（关键词检索 JOIN kb_document 已带 d.deleted=0，不泄露）
DELETE dc FROM document_chunk dc
JOIN kb_document d ON d.id = dc.document_id
WHERE d.deleted = 1;

DROP TEMPORARY TABLE tmp_keep;

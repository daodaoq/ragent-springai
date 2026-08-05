-- ============================================
-- P8: 知识库残留数据清理
--
-- 背景（2026-08-05 核实）：活跃数据（deleted=0）本就没有同名重复文档。
-- 早前"同名计数"看到的重复是【逻辑删除的历史残留】——重复上传/删除文档时，
-- 旧文档被应用层置 deleted=1（@TableLogic 不可见），属正常累积而非数据损坏。
--
-- 已执行的实际清理：物理删除全部 deleted=1 的死行（它们无切片、无向量，
-- 纯占表空间）。清理后三方完全一致：46 文档 / 869 切片 / Qdrant 869 向量。
--
-- 本脚本幂等：清理后重复执行无操作。执行前建议备份：
--   docker exec ragent-mysql mysqldump -uroot -proot ragent > backups/xxx.sql
-- ============================================

-- 物理清掉已逻辑删除的残留文档（无残留切片时可直接清）
DELETE FROM kb_document WHERE deleted = 1;

-- 若存在"已删文档仍残留切片"的异常场景（应用层删除失败未清干净），一并清：
DELETE dc FROM document_chunk dc
JOIN kb_document d ON d.id = dc.document_id
WHERE d.deleted = 1;

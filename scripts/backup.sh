#!/usr/bin/env bash
# ============================================
# ragent 数据备份脚本（P8-8f）
# 用途：备份 MySQL 业务数据（含知识库元数据/查询日志/反馈/评测结果）。
# 向量库 Qdrant 与对象存储 MinIO 按需用 docker volume 快照或 qdrant snapshot 另行备份。
# 用法：./scripts/backup.sh   （输出到 backups/ragent_<时间戳>/）
# ============================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TS="$(date +%Y%m%d_%H%M%S)"
OUT="${ROOT}/backups/ragent_${TS}"
mkdir -p "${OUT}"

echo "==> 备份 MySQL ragent 库 → ${OUT}/ragent.sql"
docker exec ragent-mysql sh -c 'exec mysqldump -uroot -proot --single-transaction --routines --triggers ragent' > "${OUT}/ragent.sql"

echo "==> 校验 dump 非空"
if ! grep -q "CREATE TABLE" "${OUT}/ragent.sql"; then
  echo "!! 备份内容为空，疑似失败，请检查 MySQL 容器状态" >&2
  exit 1
fi

echo "==> 记录当前版本与向量库状态"
docker exec ragent-qdrant curl -s http://localhost:6333/collections 2>/dev/null > "${OUT}/qdrant_collections.json" || true

echo ""
echo "✅ 备份完成：${OUT}"
echo "   恢复示例：docker exec -i ragent-mysql mysql -uroot -proot ragent < ${OUT}/ragent.sql"
echo "   提示：Qdrant/MinIO 数据在 named volume（qdrant-data / minio-data）中，如需完整备份请另做卷快照或 qdrant snapshot。"

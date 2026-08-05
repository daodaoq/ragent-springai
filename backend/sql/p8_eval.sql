-- ============================================
-- P8-6b: 新增 RAG 评测结果表（历史对比/回归追踪）
-- 对已存在的库执行一次（CREATE TABLE IF NOT EXISTS，可重复执行）
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

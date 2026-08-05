-- ============================================
-- P9: 多知识库——共享池 + 分级管理
--
-- 模型（用户已决策）：所有登录用户可见全部知识库并都可检索；仅 TEACHER/ADMIN 创建/管理库；学生只读。
-- 无用户-知识库成员表，owner_id 仅作记录（展示"创建人"），不参与检索过滤。
-- 检索按 kb_id 收窄（聊天页 KB 下拉；null=全部库），正确性由 DB 侧 kb_document.kb_id 保证。
--
-- 对已存在的库执行一次（勿重复；重复执行会因列已存在报错，无副作用）；新装见 schema.sql（已并入）。
-- ============================================

-- 知识库表（AUTO_INCREMENT；id=1 保留给默认库，历史文档/评测文档归此）
CREATE TABLE IF NOT EXISTS `kb` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键(自增；1 保留默认库)',
  `name`        VARCHAR(100) NOT NULL COMMENT '知识库名称',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '描述',
  `owner_id`    BIGINT       DEFAULT NULL COMMENT '创建人(sys_user.id，仅记录用)',
  `is_default`  TINYINT      NOT NULL DEFAULT 0 COMMENT '默认库: 1=是(历史/评测文档归此，不可删除)',
  `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0否 1是',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库';

-- 幂等插入默认库（仅当尚无默认库时）
INSERT INTO `kb` (`id`, `name`, `description`, `is_default`, `created_at`, `updated_at`)
SELECT 1, '默认知识库', '系统默认知识库：历史文档与评测文档归入此库', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `kb` WHERE `is_default` = 1);

-- kb_document 补所属库列 + 回填默认库 + 索引（一次性；重复执行报列已存在/索引已存在，无副作用）
ALTER TABLE `kb_document` ADD COLUMN `kb_id` BIGINT DEFAULT NULL COMMENT '所属知识库ID(kb.id)' AFTER `id`;
UPDATE `kb_document` SET `kb_id` = 1 WHERE `kb_id` IS NULL;
CREATE INDEX `idx_kb_id` ON `kb_document` (`kb_id`);

-- rag_query_log 记录本次检索限定的知识库（便于按库分析查询质量）
ALTER TABLE `rag_query_log` ADD COLUMN `kb_id` BIGINT DEFAULT NULL COMMENT '本次检索限定的知识库ID(NULL=全部库)' AFTER `error`;

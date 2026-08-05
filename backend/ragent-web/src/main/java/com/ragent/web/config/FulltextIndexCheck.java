package com.ragent.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P8-8e：启动自检 FULLTEXT ngram 索引是否存在（p4_retrieval.sql 需手动执行一次）。
 * 索引缺失时关键词通道会静默降级为仅向量——这里在启动时打清晰 WARN，避免"混合检索退化成纯向量"无人知晓。
 */
@Slf4j
@Component
public class FulltextIndexCheck implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public FulltextIndexCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_chunk' "
                            + "AND INDEX_NAME = 'ft_content_ngram'",
                    Integer.class);
            if (n == null || n == 0) {
                log.warn("══════════════════════════════════════════════════════════");
                log.warn("检测到 document_chunk 缺少 FULLTEXT 索引 ft_content_ngram：");
                log.warn("关键词检索通道将降级为仅向量（混合检索失效）。请手动执行一次：");
                log.warn("  docker exec -i ragent-mysql mysql -uroot -proot ragent < backend/sql/p4_retrieval.sql");
                log.warn("══════════════════════════════════════════════════════════");
            } else {
                log.info("FULLTEXT ngram 索引正常（ft_content_ngram）");
            }
        } catch (Exception e) {
            log.warn("FULLTEXT 索引自检失败（不影响启动）: {}", e.getMessage());
        }
    }
}

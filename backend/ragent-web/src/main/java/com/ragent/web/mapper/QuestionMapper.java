package com.ragent.web.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragent.web.entity.Question;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface QuestionMapper extends BaseMapper<Question> {

    /** 每日提问数趋势（近 N 天，按创建日期分组）。逻辑删除需手动过滤。 */
    @Select("""
            SELECT DATE(created_at) AS created_date, COUNT(*) AS cnt
            FROM question
            WHERE deleted = 0 AND created_at >= #{startDate}
            GROUP BY DATE(created_at)
            ORDER BY created_date
            """)
    List<TrendRow> questionTrend(@Param("startDate") LocalDateTime startDate);

    /** 标签分布：每个标签下未删除问题的数量。question_tag 无 deleted 列，不过滤。 */
    @Select("""
            SELECT t.name AS tag_name, COUNT(qt.question_id) AS cnt
            FROM question_tag qt
            JOIN question q ON q.id = qt.question_id AND q.deleted = 0
            JOIN tag t ON t.id = qt.tag_id AND t.deleted = 0
            GROUP BY t.id, t.name
            ORDER BY cnt DESC
            """)
    List<TagCountRow> tagDistribution();

    /** Top 提问者 */
    @Select("""
            SELECT q.user_id, u.nickname, COUNT(*) AS cnt
            FROM question q
            JOIN sys_user u ON u.id = q.user_id AND u.deleted = 0
            WHERE q.deleted = 0
            GROUP BY q.user_id, u.nickname
            ORDER BY cnt DESC
            LIMIT #{limit}
            """)
    List<TopAskerRow> topAskers(@Param("limit") int limit);

    /** 列名与 record 组件通过 map-underscore-to-camel-case 自动映射（-parameters 编译器参数）。
     *  cnt 用 primitive long：Jackson 已将 boxed Long 序列化为字符串，计数保持数字避免破坏看板。 */
    record TrendRow(LocalDate createdDate, long cnt) {
    }

    record TagCountRow(String tagName, long cnt) {
    }

    record TopAskerRow(Long userId, String nickname, long cnt) {
    }
}

package com.ragent.web.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ragent.web.dto.FeedbackVO;
import com.ragent.web.entity.AiFeedback;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {

    /** 分页查询反馈明细（LEFT JOIN sys_user 取昵称），rating 为 null 时不过滤 */
    @Select("""
            <script>
            SELECT f.id, f.user_id AS userId, u.nickname, f.conversation_id AS conversationId,
                   f.question, f.answer, f.rating, f.trace_id AS traceId, f.created_at AS createdAt
            FROM ai_feedback f
            LEFT JOIN sys_user u ON f.user_id = u.id
            <where>
              <if test="rating != null">AND f.rating = #{rating}</if>
            </where>
            ORDER BY f.created_at DESC
            </script>
            """)
    IPage<FeedbackVO> pageFeedbacks(IPage<?> page, @Param("rating") Integer rating);
}

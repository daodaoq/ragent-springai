package com.ragent.web.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.web.dto.FeedbackDTO;
import com.ragent.web.entity.AiFeedback;
import com.ragent.web.mapper.AiFeedbackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 回答反馈：赞/踩提交 + 统计（看板用）
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final AiFeedbackMapper aiFeedbackMapper;

    public void submit(FeedbackDTO dto) {
        if (dto.rating() == null || (dto.rating() != 1 && dto.rating() != -1)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "rating 必须为 1（赞）或 -1（踩）");
        }
        AiFeedback feedback = new AiFeedback();
        feedback.setConversationId(dto.conversationId());
        feedback.setQuestion(dto.question());
        feedback.setAnswer(dto.answer());
        feedback.setRating(dto.rating());
        if (StpUtil.isLogin()) {
            feedback.setUserId(StpUtil.getLoginIdAsLong());
        }
        aiFeedbackMapper.insert(feedback);
    }

    public FeedbackStats stats() {
        long total = aiFeedbackMapper.selectCount(new LambdaQueryWrapper<>());
        long up = aiFeedbackMapper.selectCount(new LambdaQueryWrapper<AiFeedback>().eq(AiFeedback::getRating, 1));
        long down = total - up;
        double upRate = total == 0 ? 0 : up * 100.0 / total;
        return new FeedbackStats(total, up, down, upRate);
    }

    public record FeedbackStats(long total, long up, long down, double upRate) {
    }
}

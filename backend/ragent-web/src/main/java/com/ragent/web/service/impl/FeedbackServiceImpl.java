package com.ragent.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.result.PageResult;
import com.ragent.web.dto.FeedbackDTO;
import com.ragent.web.dto.FeedbackVO;
import com.ragent.web.entity.AiFeedback;
import com.ragent.web.mapper.AiFeedbackMapper;
import com.ragent.web.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 回答反馈实现
 */
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final AiFeedbackMapper aiFeedbackMapper;

    @Override
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

    @Override
    public FeedbackStats stats() {
        long total = aiFeedbackMapper.selectCount(new LambdaQueryWrapper<>());
        long up = aiFeedbackMapper.selectCount(new LambdaQueryWrapper<AiFeedback>().eq(AiFeedback::getRating, 1));
        long down = total - up;
        double upRate = total == 0 ? 0 : up * 100.0 / total;
        return new FeedbackStats(total, up, down, upRate);
    }

    @Override
    public PageResult<FeedbackVO> page(Integer rating, int pageNum, int pageSize) {
        Integer filter = (rating == null || rating == 0) ? null : rating;
        IPage<FeedbackVO> result = aiFeedbackMapper.pageFeedbacks(new Page<>(pageNum, pageSize), filter);
        return PageResult.of(result.getTotal(), pageNum, pageSize, result.getRecords());
    }
}

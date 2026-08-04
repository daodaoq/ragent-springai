package com.ragent.web.service;

import com.ragent.common.result.PageResult;
import com.ragent.web.dto.FeedbackDTO;
import com.ragent.web.dto.FeedbackVO;

/**
 * AI 回答反馈：赞/踩提交 + 统计（看板用）
 */
public interface FeedbackService {

    void submit(FeedbackDTO dto);

    FeedbackStats stats();

    /** 分页明细（管理端）；rating 为 null 或 0 时不过滤 */
    PageResult<FeedbackVO> page(Integer rating, int pageNum, int pageSize);

    record FeedbackStats(long total, long up, long down, double upRate) {
    }
}

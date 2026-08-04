package com.ragent.web.service;

import com.ragent.common.result.PageResult;
import com.ragent.web.dto.CreateQuestionDTO;
import com.ragent.web.dto.QuestionVO;

/**
 * 问题服务：列表、详情、创建、采纳
 */
public interface QuestionService {

    PageResult<QuestionVO> list(long pageNum, long pageSize, Long tagId, String keyword);

    QuestionVO detail(Long id);

    /** 只读详情（不自增浏览数），供 Agent 工具调用 */
    QuestionVO detailReadOnly(Long id);

    QuestionVO create(CreateQuestionDTO dto);

    /** 采纳回答（仅提问人可操作） */
    void accept(Long questionId, Long answerId);
}

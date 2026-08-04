package com.ragent.web.service;

import com.ragent.web.dto.AnswerVO;
import com.ragent.web.dto.CreateAnswerDTO;
import com.ragent.web.entity.Answer;

import java.util.List;

/**
 * 回答服务
 */
public interface AnswerService {

    AnswerVO create(CreateAnswerDTO dto);

    List<AnswerVO> listByQuestionId(Long questionId);

    AnswerVO toVO(Answer answer);
}

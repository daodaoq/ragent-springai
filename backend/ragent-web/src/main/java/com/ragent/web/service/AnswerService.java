package com.ragent.web.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.web.dto.AnswerVO;
import com.ragent.web.dto.CreateAnswerDTO;
import com.ragent.web.entity.Answer;
import com.ragent.web.entity.Question;
import com.ragent.web.mapper.AnswerMapper;
import com.ragent.web.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回答服务
 */
@Service
@RequiredArgsConstructor
public class AnswerService {

    private final AnswerMapper answerMapper;
    private final QuestionMapper questionMapper;
    private final UserService userService;

    public AnswerVO create(CreateAnswerDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Question question = questionMapper.selectById(dto.questionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "问题不存在");
        }
        Answer answer = new Answer();
        answer.setQuestionId(dto.questionId());
        answer.setUserId(userId);
        answer.setContent(dto.content());
        answer.setIsAccepted(0);
        answerMapper.insert(answer);

        questionMapper.update(null, new LambdaUpdateWrapper<Question>()
                .eq(Question::getId, dto.questionId())
                .setSql("answer_count = answer_count + 1"));
        return toVO(answer);
    }

    public List<AnswerVO> listByQuestionId(Long questionId) {
        List<Answer> answers = answerMapper.selectList(new LambdaQueryWrapper<Answer>()
                .eq(Answer::getQuestionId, questionId)
                .orderByDesc(Answer::getIsAccepted)
                .orderByAsc(Answer::getCreatedAt));
        return answers.stream().map(this::toVO).toList();
    }

    public AnswerVO toVO(Answer answer) {
        return new AnswerVO(answer.getId(), answer.getQuestionId(), answer.getUserId(), answer.getContent(),
                answer.getIsAccepted() != null && answer.getIsAccepted() == 1,
                answer.getCreatedAt(),
                userService.toVO(userService.getRequired(answer.getUserId())));
    }
}

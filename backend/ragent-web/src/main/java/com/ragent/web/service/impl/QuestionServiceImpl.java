package com.ragent.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.result.PageResult;
import com.ragent.web.dto.CreateQuestionDTO;
import com.ragent.web.dto.QuestionVO;
import com.ragent.web.entity.Answer;
import com.ragent.web.entity.Question;
import com.ragent.web.entity.QuestionTag;
import com.ragent.web.mapper.AnswerMapper;
import com.ragent.web.mapper.QuestionMapper;
import com.ragent.web.mapper.QuestionTagMapper;
import com.ragent.web.service.AnswerService;
import com.ragent.web.service.QuestionService;
import com.ragent.web.service.TagService;
import com.ragent.web.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问题服务实现：列表、详情、创建、采纳
 */
@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;
    private final QuestionTagMapper questionTagMapper;
    private final AnswerMapper answerMapper;
    private final TagService tagService;
    private final AnswerService answerService;
    private final UserService userService;

    @Override
    public PageResult<QuestionVO> list(long pageNum, long pageSize, Long tagId, String keyword) {
        LambdaQueryWrapper<Question> qw = new LambdaQueryWrapper<>();
        qw.orderByDesc(Question::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(Question::getTitle, keyword).or().like(Question::getContent, keyword));
        }
        if (tagId != null) {
            List<Long> qids = questionTagMapper.selectList(
                            new LambdaQueryWrapper<QuestionTag>().eq(QuestionTag::getTagId, tagId))
                    .stream().map(QuestionTag::getQuestionId).toList();
            if (qids.isEmpty()) {
                return PageResult.of(0, pageNum, pageSize, List.of());
            }
            qw.in(Question::getId, qids);
        }
        Page<Question> page = questionMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        List<QuestionVO> records = page.getRecords().stream().map(this::toListVO).toList();
        return PageResult.of(page.getTotal(), pageNum, pageSize, records);
    }

    @Override
    public QuestionVO detail(Long id) {
        QuestionVO vo = detailReadOnly(id);
        questionMapper.update(null, new LambdaUpdateWrapper<Question>()
                .eq(Question::getId, id)
                .setSql("view_count = view_count + 1"));
        return vo;
    }

    @Override
    public QuestionVO detailReadOnly(Long id) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "问题不存在");
        }
        return toDetailVO(question);
    }

    @Override
    public QuestionVO create(CreateQuestionDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Question question = new Question();
        question.setTitle(dto.title());
        question.setContent(dto.content());
        question.setUserId(userId);
        question.setStatus("OPEN");
        question.setViewCount(0);
        question.setAnswerCount(0);
        questionMapper.insert(question);

        List<Long> tagIds = tagService.ensureTags(dto.tags());
        tagService.linkQuestion(question.getId(), tagIds);

        return detail(question.getId());
    }

    @Override
    public void accept(Long questionId, Long answerId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "问题不存在");
        }
        if (!question.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有提问人可以采纳回答");
        }
        Answer answer = answerMapper.selectById(answerId);
        if (answer == null || !answer.getQuestionId().equals(questionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "回答不存在");
        }
        // 先取消该问题下所有采纳，再采纳指定回答
        answerMapper.update(null, new LambdaUpdateWrapper<Answer>()
                .eq(Answer::getQuestionId, questionId)
                .set(Answer::getIsAccepted, 0));
        answerMapper.update(null, new LambdaUpdateWrapper<Answer>()
                .eq(Answer::getId, answerId)
                .set(Answer::getIsAccepted, 1));
        questionMapper.update(null, new LambdaUpdateWrapper<Question>()
                .eq(Question::getId, questionId)
                .set(Question::getBestAnswerId, answerId)
                .set(Question::getStatus, "RESOLVED"));
    }

    private QuestionVO toListVO(Question q) {
        return new QuestionVO(q.getId(), q.getTitle(), q.getContent(), q.getUserId(), q.getStatus(),
                q.getBestAnswerId(), q.getViewCount(), q.getAnswerCount(), q.getCreatedAt(),
                userService.toVO(userService.getRequired(q.getUserId())),
                tagService.listByQuestionId(q.getId()), List.of());
    }

    private QuestionVO toDetailVO(Question q) {
        return new QuestionVO(q.getId(), q.getTitle(), q.getContent(), q.getUserId(), q.getStatus(),
                q.getBestAnswerId(), q.getViewCount(), q.getAnswerCount(), q.getCreatedAt(),
                userService.toVO(userService.getRequired(q.getUserId())),
                tagService.listByQuestionId(q.getId()), answerService.listByQuestionId(q.getId()));
    }
}

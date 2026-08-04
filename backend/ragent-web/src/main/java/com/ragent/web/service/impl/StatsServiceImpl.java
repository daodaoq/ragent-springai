package com.ragent.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.web.entity.Answer;
import com.ragent.web.entity.Question;
import com.ragent.web.entity.Tag;
import com.ragent.web.entity.User;
import com.ragent.web.mapper.AnswerMapper;
import com.ragent.web.mapper.QuestionMapper;
import com.ragent.web.mapper.TagMapper;
import com.ragent.web.mapper.UserMapper;
import com.ragent.web.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据统计服务实现（看板）
 */
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final QuestionMapper questionMapper;
    private final AnswerMapper answerMapper;
    private final UserMapper userMapper;
    private final TagMapper tagMapper;

    @Override
    public Overview overview() {
        return new Overview(
                questionMapper.selectCount(new LambdaQueryWrapper<>()),
                answerMapper.selectCount(new LambdaQueryWrapper<>()),
                userMapper.selectCount(new LambdaQueryWrapper<>()),
                tagMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    @Override
    public List<QuestionMapper.TrendRow> questionTrend(int days) {
        int d = days <= 0 ? 14 : Math.min(days, 90);
        LocalDateTime start = LocalDate.now().minusDays(d - 1L).atStartOfDay();
        return questionMapper.questionTrend(start);
    }

    @Override
    public List<QuestionMapper.TagCountRow> tagDistribution() {
        return questionMapper.tagDistribution();
    }

    @Override
    public List<QuestionMapper.TopAskerRow> topAskers(int limit) {
        int n = limit <= 0 ? 5 : Math.min(limit, 20);
        return questionMapper.topAskers(n);
    }
}

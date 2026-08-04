package com.ragent.web.service;

import com.ragent.web.mapper.QuestionMapper;

import java.util.List;

/**
 * 数据统计服务（看板）。
 * 总览计数走 BaseMapper.selectCount 自动带逻辑删除；聚合查询为手写 @Select（手动 deleted=0）。
 */
public interface StatsService {

    Overview overview();

    List<QuestionMapper.TrendRow> questionTrend(int days);

    List<QuestionMapper.TagCountRow> tagDistribution();

    List<QuestionMapper.TopAskerRow> topAskers(int limit);

    record Overview(long questions, long answers, long users, long tags) {
    }
}

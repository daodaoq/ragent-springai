package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.common.result.Result;
import com.ragent.web.mapper.QuestionMapper;
import com.ragent.web.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据统计接口（看板）
 */
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 总览：问题数 / 回答数 / 用户数 / 标签数 */
    @GetMapping("/overview")
    @SaCheckLogin
    public Result<StatsService.Overview> overview() {
        return Result.success(statsService.overview());
    }

    /** 每日提问趋势（默认近 14 天） */
    @GetMapping("/question-trend")
    @SaCheckLogin
    public Result<List<QuestionMapper.TrendRow>> questionTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.success(statsService.questionTrend(days));
    }

    /** 标签分布 */
    @GetMapping("/tag-distribution")
    @SaCheckLogin
    public Result<List<QuestionMapper.TagCountRow>> tagDistribution() {
        return Result.success(statsService.tagDistribution());
    }

    /** Top 提问者（默认 5 名） */
    @GetMapping("/top-askers")
    @SaCheckLogin
    public Result<List<QuestionMapper.TopAskerRow>> topAskers(@RequestParam(defaultValue = "5") int limit) {
        return Result.success(statsService.topAskers(limit));
    }
}

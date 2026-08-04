package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.common.result.Result;
import com.ragent.web.dto.FeedbackDTO;
import com.ragent.web.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 回答反馈接口（赞/踩）
 */
@RestController
@RequestMapping("/ai/feedback")
@RequiredArgsConstructor
public class AiFeedbackController {

    private final FeedbackService feedbackService;

    /** 提交反馈（公开，未登录也能评） */
    @PostMapping
    public Result<Void> submit(@RequestBody FeedbackDTO dto) {
        feedbackService.submit(dto);
        return Result.success();
    }

    /** 反馈统计（看板，需登录） */
    @GetMapping("/stats")
    @SaCheckLogin
    public Result<FeedbackService.FeedbackStats> stats() {
        return Result.success(feedbackService.stats());
    }
}

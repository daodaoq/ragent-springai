package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ragent.common.result.PageResult;
import com.ragent.common.result.Result;
import com.ragent.web.dto.FeedbackDTO;
import com.ragent.web.dto.FeedbackVO;
import com.ragent.web.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 反馈明细分页（管理端，仅管理员/教师可见）；rating: 0/缺省=全部，1=有帮助，-1=不准确 */
    @GetMapping("/list")
    @SaCheckRole(value = {"ADMIN", "TEACHER"}, mode = SaMode.OR)
    public Result<PageResult<FeedbackVO>> list(@RequestParam(required = false) Integer rating,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(feedbackService.page(rating, page, pageSize));
    }
}

package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.common.result.PageResult;
import com.ragent.common.result.Result;
import com.ragent.web.dto.CreateQuestionDTO;
import com.ragent.web.dto.QuestionVO;
import com.ragent.web.service.QuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问题接口
 */
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @GetMapping("/list")
    public Result<PageResult<QuestionVO>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String keyword) {
        return Result.success(questionService.list(pageNum, pageSize, tagId, keyword));
    }

    @GetMapping("/{id}")
    public Result<QuestionVO> detail(@PathVariable Long id) {
        return Result.success(questionService.detail(id));
    }

    @PostMapping
    @SaCheckLogin
    public Result<QuestionVO> create(@Valid @RequestBody CreateQuestionDTO dto) {
        return Result.success(questionService.create(dto));
    }

    @PostMapping("/{id}/accept/{answerId}")
    @SaCheckLogin
    public Result<Void> accept(@PathVariable Long id, @PathVariable Long answerId) {
        questionService.accept(id, answerId);
        return Result.success();
    }
}

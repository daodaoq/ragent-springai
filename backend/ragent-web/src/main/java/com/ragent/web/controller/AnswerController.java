package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.common.result.Result;
import com.ragent.web.dto.AnswerVO;
import com.ragent.web.dto.CreateAnswerDTO;
import com.ragent.web.service.AnswerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回答接口
 */
@RestController
@RequestMapping("/answers")
@RequiredArgsConstructor
public class AnswerController {

    private final AnswerService answerService;

    @PostMapping
    @SaCheckLogin
    public Result<AnswerVO> create(@Valid @RequestBody CreateAnswerDTO dto) {
        return Result.success(answerService.create(dto));
    }
}

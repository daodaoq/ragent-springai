package com.ragent.web.eval;

import com.ragent.ai.eval.EvalService;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测接口：运行检索与回答质量评测，返回量化指标
 */
@RestController
@RequestMapping("/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    @PostMapping("/run")
    public Result<EvalService.EvalReport> run() {
        return Result.success(evalService.run());
    }
}

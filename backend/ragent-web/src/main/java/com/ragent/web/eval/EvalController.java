package com.ragent.web.eval;

import com.ragent.ai.eval.EvalService;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测接口：运行检索与回答质量评测，返回量化指标。
 * processed 可选：true=检索走查询处理管线（默认），false=原样检索（A/B 基线）。
 */
@RestController
@RequestMapping("/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    @PostMapping("/run")
    public Result<EvalService.EvalReport> run(@RequestBody(required = false) EvalRunRequest req) {
        boolean processed = req == null || req.processed();
        return Result.success(evalService.run(processed));
    }

    public record EvalRunRequest(boolean processed) {
    }
}

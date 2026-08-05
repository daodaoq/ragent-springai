package com.ragent.web.eval;

import com.ragent.ai.eval.EvalService;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

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
        boolean withAnswer = req == null || req.withAnswer() == null || req.withAnswer();
        return Result.success(evalService.run(processed, withAnswer));
    }

    /** P8-6b：评测历史（时间倒序，不含 detail_json 大字段），供趋势/回归对比 */
    @GetMapping("/history")
    public Result<List<EvalHistoryItem>> history(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(evalService.history(limit).stream()
                .map(e -> new EvalHistoryItem(
                        String.valueOf(e.getId()), e.getProcessed(), e.getWithAnswer(), e.getTotalCases(),
                        e.getRecall(), e.getPrecision(), e.getMrr(), e.getNdcg(),
                        e.getAvgFaithfulness(), e.getAvgRelevance(), e.getCitationRate(), e.getCreatedAt()))
                .toList());
    }

    public record EvalRunRequest(boolean processed, Boolean withAnswer) {
    }

    /** 历史条目（省略 detail_json） */
    public record EvalHistoryItem(String id, Boolean processed, Boolean withAnswer, Integer totalCases,
                                  Double recall, Double precision, Double mrr, Double ndcg,
                                  Double avgFaithfulness, Double avgRelevance, Double citationRate,
                                  LocalDateTime createdAt) {
    }
}

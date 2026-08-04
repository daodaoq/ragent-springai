package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.ai.service.ChunkQualityService;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 切片质量评估接口：全局切片参数设置 + 质量报告（供「切片质量」页）
 */
@RestController
@RequestMapping("/kb/quality")
@RequiredArgsConstructor
@SaCheckLogin
public class ChunkQualityController {

    private final ChunkQualityService qualityService;

    /** 全局切片参数（有效默认值） */
    @GetMapping("/settings")
    public Result<ChunkQualityService.ChunkSettings> settings() {
        return Result.success(qualityService.getSettings());
    }

    /** 更新全局切片参数 */
    @PutMapping("/settings")
    public Result<Void> updateSettings(@RequestBody ChunkQualityService.ChunkSettings settings) {
        qualityService.updateSettings(settings);
        return Result.success();
    }

    /** 质量报告：docId 缺省 = 全库聚合，指定 = 单文档 */
    @GetMapping("/report")
    public Result<ChunkQualityService.ChunkQualityReport> report(@RequestParam(required = false) Long docId) {
        return Result.success(qualityService.qualityReport(docId));
    }
}

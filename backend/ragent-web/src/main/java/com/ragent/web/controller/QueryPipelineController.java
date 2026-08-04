package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ragent.ai.service.QueryPipelineService;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 查询处理管线编排接口：阶段启停/排序（供「切片质量」页可视化编排，DB 即运行时真相，立即生效）。
 */
@RestController
@RequestMapping("/kb/query/stages")
@RequiredArgsConstructor
@SaCheckLogin
public class QueryPipelineController {

    private final QueryPipelineService pipelineService;

    /** 全部阶段配置（按 sort_order 升序；首次访问自动播种内置 7 阶段） */
    @GetMapping
    public Result<List<QueryPipelineService.StageConfig>> list() {
        return Result.success(pipelineService.listStages());
    }

    /** 批量保存：按 name upsert（enabled/sortOrder） */
    @PutMapping
    public Result<Void> update(@RequestBody List<QueryPipelineService.StageConfig> configs) {
        pipelineService.updateStages(configs);
        return Result.success();
    }
}

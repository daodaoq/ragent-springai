package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ragent.ai.entity.RagQueryLog;
import com.ragent.ai.service.RagQueryLogService;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 查询日志接口：查看真实查询轨迹（供评测集挖掘 / 质量分析）。仅管理员/教师可见。
 */
@RestController
@RequestMapping("/kb/query-log")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckRole(value = {"ADMIN", "TEACHER"}, mode = SaMode.OR)
public class QueryLogController {

    private final RagQueryLogService queryLogService;

    /** 分页查询（按时间倒序） */
    @GetMapping
    public Result<IPage<RagQueryLog>> list(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(queryLogService.list(pageNum, pageSize));
    }
}

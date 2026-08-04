package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ragent.common.result.Result;
import com.ragent.web.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 日志查看接口（仅 ADMIN）
 */
@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
public class LogController {

    private final LogService logService;

    /** 分页查询日志 */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String keyword) {
        return Result.success(logService.search(pageNum, pageSize, level, module, keyword));
    }
}

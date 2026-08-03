package com.ragent.web.controller;

import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0 健康检查：探测应用、MySQL、Redis 连通性
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("app", "ragent");
        status.put("time", LocalDateTime.now());
        status.put("mysql", checkMysql());
        status.put("redis", checkRedis());
        return Result.success(status);
    }

    private String checkMysql() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private String checkRedis() {
        try {
            String pong = stringRedisTemplate.getConnectionFactory()
                    .getConnection().ping();
            return "UP(" + pong + ")";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }
}

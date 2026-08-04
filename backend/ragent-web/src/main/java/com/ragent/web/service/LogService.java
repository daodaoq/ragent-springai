package com.ragent.web.service;

import java.util.Map;

/**
 * 日志查询服务（从 Elasticsearch 读取）
 */
public interface LogService {

    /**
     * 分页查询日志
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @param level    日志级别筛选（INFO / WARN / ERROR / DEBUG），可选
     * @param module   模块筛选（controller / service 等），可选
     * @param keyword  消息关键词，可选
     * @return {total, pages, list}
     */
    Map<String, Object> search(int pageNum, int pageSize, String level, String module, String keyword);
}

package com.ragent.web.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragent.web.service.LogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 Elasticsearch 查询日志
 */
@Slf4j
@Service
public class LogServiceImpl implements LogService {

    /** ES 默认 max_result_window，超出后深分页会报错，这里做上限保护 */
    private static final int MAX_RESULT_WINDOW = 10000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String esUrl;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    public LogServiceImpl(@Value("${ragent.logs.es-url:http://localhost:9200}") String esUrl,
                          ObjectMapper objectMapper) {
        this.esUrl = esUrl;
        this.objectMapper = objectMapper;
        // 显式超时，避免 ES 无响应时请求长时间挂起
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public Map<String, Object> search(int pageNum, int pageSize,
                                       String level, String module, String keyword) {
        pageSize = Math.min(Math.max(pageSize, 1), 100);
        pageNum = Math.max(pageNum, 1);
        int from = (pageNum - 1) * pageSize;
        // 深分页保护：超过 ES max_result_window 时截断，避免查询报错
        if (from >= MAX_RESULT_WINDOW) {
            from = Math.max(MAX_RESULT_WINDOW - pageSize, 0);
        }

        ObjectNode query = buildQuery(level, module, keyword);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("from", from);
        body.put("size", pageSize);
        body.set("query", query);
        body.putObject("sort")
                .putArray("@timestamp")
                .addObject().put("order", "desc");

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            String resp = restTemplate.postForObject(
                    esUrl + "/ragent-logs-*/_search", entity, String.class);

            return parseResponse(resp, pageNum, pageSize);
        } catch (Exception e) {
            log.warn("查询 ES 日志失败: {}", e.getMessage());
            return emptyResult(pageNum, pageSize);
        }
    }

    private ObjectNode buildQuery(String level, String module, String keyword) {
        ObjectNode bool = objectMapper.createObjectNode();
        ArrayNode must = objectMapper.createArrayNode();

        // logstash 不再转小写，ES 中 level 与 logback 输出一致（INFO/WARN/ERROR/DEBUG）
        if (level != null && !level.isBlank()) {
            must.add(matchTerm("level", level.toUpperCase()));
        }
        if (module != null && !module.isBlank()) {
            must.add(matchTerm("module", module));
        }
        if (keyword != null && !keyword.isBlank()) {
            ObjectNode multi = objectMapper.createObjectNode();
            ObjectNode mq = objectMapper.createObjectNode();
            mq.put("query", keyword);
            mq.putArray("fields").add("message").add("logger").add("action").add("userId");
            multi.set("multi_match", mq);
            must.add(multi);
        }

        bool.set("must", must);
        ObjectNode query = objectMapper.createObjectNode();
        query.set("bool", bool);
        return query;
    }

    private ObjectNode matchTerm(String field, String value) {
        ObjectNode term = objectMapper.createObjectNode();
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put(field, value);
        term.set("term", inner);
        return term;
    }

    private Map<String, Object> parseResponse(String resp, int pageNum, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(resp);
            long total = root.path("hits").path("total").path("value").asLong(0);
            long pages = (total + pageSize - 1) / pageSize;

            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", hit.path("_id").asText(""));

                // 时间戳转换
                String ts = src.path("@timestamp").asText("");
                try {
                    Instant instant = Instant.parse(ts);
                    item.put("timestamp", FMT.format(instant));
                } catch (Exception e) {
                    item.put("timestamp", ts);
                }

                item.put("level", src.path("level").asText(""));
                item.put("message", src.path("message").asText(""));
                item.put("logger", src.path("logger").asText(""));
                item.put("thread", src.path("thread").asText(""));
                item.put("module", src.path("module").asText(""));
                item.put("action", src.path("action").asText(""));
                item.put("userId", src.path("userId").asText(""));

                list.add(item);
            }

            result.put("total", total);
            result.put("pages", pages);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            result.put("list", list);
        } catch (Exception e) {
            log.warn("解析 ES 响应失败: {}", e.getMessage());
            return emptyResult(pageNum, pageSize);
        }
        return result;
    }

    private Map<String, Object> emptyResult(int pageNum, int pageSize) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("total", 0L);
        empty.put("pages", 0);
        empty.put("pageNum", pageNum);
        empty.put("pageSize", pageSize);
        empty.put("list", List.of());
        return empty;
    }
}

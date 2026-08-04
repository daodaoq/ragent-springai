package com.ragent.web.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置：雪花 ID（boxed Long）序列化为字符串。
 * <p>
 * 雪花 ID 超过 JS Number.MAX_SAFE_INTEGER（2^53），前端 JSON.parse 会丢失精度，
 * 导致回传 ID 失真（如 2084270782817935361 → 2084270782817935400）→ 详情 404「问题不存在」。
 * Long 转 String 后前端全程按字符串处理，精度无损。
 * 仅影响 boxed Long；primitive long（分页 total、统计计数）保持数字。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
    }
}

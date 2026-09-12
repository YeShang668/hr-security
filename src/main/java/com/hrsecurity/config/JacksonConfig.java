package com.hrsecurity.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * Jackson 日期格式统一配置（第 5 周补）。
 *
 * 背景（BUG5-3）：application.yml 里的 spring.jackson.date-format 只作用于 java.util.Date，
 * 对 LocalDateTime/LocalDate 无效，所以直接返回实体的接口（部门列表、角色列表）会吐出
 * "2026-09-12T19:06:44" 这种带 T 的 ISO 串，和 VO 用 @JsonFormat 指定的格式不一致，
 * 前端同一天在两页看到两种写法。
 *
 * 这里给 LocalDateTime/LocalDate 注册全局序列化器，统一成 "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-dd"；
 * 同时注册反序列化器，避免前端传 "2026-09-12" 时绑不进 LocalDate。
 * VO 上已有的 @JsonFormat 优先级更高，两者取值一致，不会冲突。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsr310DateTimeCustomizer() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(DATE_TIME), new LocalDateSerializer(DATE))
                .deserializers(new LocalDateTimeDeserializer(DATE_TIME), new LocalDateDeserializer(DATE));
    }
}

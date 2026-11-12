package com.ethan.note.image;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class RedisConfig {

    /** 이미지 바이트를 그대로 넣고 빼는 템플릿(키는 문자열, 값은 byte[]). */
    @Bean
    public RedisTemplate<String, byte[]> bytesRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        t.setKeySerializer(RedisSerializer.string());
        t.setValueSerializer(RedisSerializer.byteArray());
        t.afterPropertiesSet();
        return t;
    }
}

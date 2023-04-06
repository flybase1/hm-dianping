package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * redisson配置redis
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient() {
        // 1. Create config object
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.209.133:6379").setPassword("root");
        return Redisson.create(config);
    }
}

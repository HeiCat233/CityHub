package com.tickethub.config;

import com.tickethub.utils.RedisConstants;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import cn.hutool.core.util.StrUtil;

@Configuration
public class RedissonConfig {

    /**
     * 注意：这里必须读配置，不能写死 127.0.0.1。
     *
     * 原因：在 Docker 容器里，127.0.0.1 指向容器自己，
     * 而 Redis 是另一个容器（服务名 redis）。
     * 写死 localhost 会导致启动时连不上 Redis，
     * 报 Connection refused: /127.0.0.1:6379，然后反复重试。
     *
     * 本地 IDE 直接跑时，application.yaml 的默认值是 127.0.0.1，
     * 所以两种场景都能工作。
     */
    @Value("${spring.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(StrUtil.isBlank(password) ? null : password);
        return Redisson.create(config);
    }
}

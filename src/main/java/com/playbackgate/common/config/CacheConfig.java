package com.playbackgate.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableCaching
@Profile("!test")
public class CacheConfig {

    public static final String CONTENTS = "contents";
    public static final String MEMBERS = "members";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(CONTENTS, MEMBERS);
        cacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .recordStats()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofSeconds(60))
        );
        return cacheManager;
    }
}

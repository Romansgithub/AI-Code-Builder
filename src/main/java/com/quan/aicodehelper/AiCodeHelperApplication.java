package com.quan.aicodehelper;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p>{@code @EnableScheduling} 为 SSE 生成任务提供两项定时能力：
 * 过期任务现场回收（内存不能只增不减），以及生成看门狗（AI 流卡死时兜底收尾）。</p>
 */
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.quan.aicodehelper.mapper")
@EnableScheduling
public class AiCodeHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeHelperApplication.class, args);
    }

}

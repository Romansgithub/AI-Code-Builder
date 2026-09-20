package com.quan.aicodehelper;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.quan.aicodehelper.mapper")
public class AiCodeHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeHelperApplication.class, args);
    }

}

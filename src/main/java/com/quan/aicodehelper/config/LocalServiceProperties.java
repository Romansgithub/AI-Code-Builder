package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地基础服务依赖（Nginx / Redis）自检配置。忘拉依赖的症状很隐蔽（静态资源 404、登录态丢失），
 * 排查时容易先怀疑代码。默认只检测不自动启动：Nginx 默认监听 80 端口，Windows 上绑定特权端口
 * 需要管理员权限，而项目的 Maven 进程是非提权的，不该为省一次复制粘贴引入权限提升风险。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.local-services")
public class LocalServiceProperties {

    /**
     * 总开关。关掉后启动时完全不做检查，行为与改造前一致。
     */
    private boolean enabled = true;

    /**
     * 检测到服务未运行时是否尝试自动启动。默认 false，理由见类注释；
     * 开启后仍需以管理员身份运行项目，否则会因权限不足而失败。
     */
    private boolean autoStart = false;

    /**
     * 检查失败（服务不可用且未自动启动）时是否阻止应用启动。默认 false：Nginx 仅影响静态资源预览，为可选依赖拦下整体太重。
     */
    private boolean failFast = false;

    /**
     * 单个服务就绪的等待上限（毫秒）。仅自动启动模式下有意义。
     */
    private long readyTimeoutMs = 15000;

    /**
     * 就绪检查的轮询间隔（毫秒）。
     */
    private long pollIntervalMs = 500;

    private Redis redis = new Redis();

    private Nginx nginx = new Nginx();

    /**
     * Redis 配置。host / port 与 spring.data.redis 分开写：后者可能是远端地址，
     * 而这里要探的是本机服务，复用同一个值会在 Redis 配到远端时得出错误结论。
     */
    @Data
    public static class Redis {

        private boolean enabled = true;

        private String host = "127.0.0.1";

        private int port = 6379;

        /**
         * 服务不可用时提示用户执行的命令。写成配置而非硬编码，安装路径因机器而异。
         */
        private String startCommand = "D:\\Redis\\start-redis.bat";
    }

    @Data
    public static class Nginx {

        private boolean enabled = true;

        private String host = "127.0.0.1";

        private int port = 80;

        private String startCommand = "D:\\nginx\\start-nginx.bat";
    }

    @PostConstruct
    public void init() {
        if (readyTimeoutMs <= 0) {
            log.warn("local-services.readyTimeoutMs 必须为正数，已重置为 15000");
            readyTimeoutMs = 15000;
        }
        if (pollIntervalMs <= 0) {
            log.warn("local-services.pollIntervalMs 必须为正数，已重置为 500");
            pollIntervalMs = 500;
        }
        if (redis.getPort() <= 0 || redis.getPort() > 65535) {
            log.warn("local-services.redis.port 非法（{}），已重置为 6379", redis.getPort());
            redis.setPort(6379);
        }
        if (nginx.getPort() <= 0 || nginx.getPort() > 65535) {
            log.warn("local-services.nginx.port 非法（{}），已重置为 80", nginx.getPort());
            nginx.setPort(80);
        }
        if (autoStart) {
            log.info("本地服务自检：enabled={}, autoStart={}（自动启动需要管理员权限），"
                            + "failFast={}, redis={}:{}, nginx={}:{}",
                    enabled, true, failFast, redis.getHost(), redis.getPort(),
                    nginx.getHost(), nginx.getPort());
        } else {
            log.info("本地服务自检：enabled={}, autoStart=false（只检查不启动），failFast={}",
                    enabled, failFast);
        }
    }

    public boolean isRedisCheckActive() {
        return enabled && redis.isEnabled();
    }

    public boolean isNginxCheckActive() {
        return enabled && nginx.isEnabled();
    }
}

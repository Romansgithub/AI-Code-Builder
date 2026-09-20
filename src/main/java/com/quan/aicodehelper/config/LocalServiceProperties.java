package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地基础服务依赖（Nginx / Redis）自检配置。
 *
 * <p><b>它解决什么问题</b>：本地开发依赖 Nginx（静态资源 + 反向代理）与 Redis
 * （会话 + 对话记忆），两者都要开机后手动拉起。忘了拉的症状很隐蔽 ——
 * 页面打得开但静态资源全 404、或者登录状态莫名其妙丢失，排查时往往先怀疑代码，
 * 实际是依赖没起。开机后第一次跑项目时顺手检查一下，比事后排查便宜得多。</p>
 *
 * <p><b>为什么默认只检查、不自动启动</b>：Nginx 默认监听 80 端口，在 Windows 上
 * 绑定这类特权端口需要管理员权限。本项目的 Maven 进程平时是非提权的，若为此
 * 要求整个项目以管理员身份运行，AI 生成的代码和 npm 构建脚本会全部继承管理员
 * 权限 —— 拿一个真实的权限提升风险去换一次复制粘贴，不划算。
 * 所以这里选择「检测 + 明确提示」，把启动动作留在用户手里。
 * 真想让项目自己拉起的，把 {@code auto-start} 设为 true 并以管理员身份运行。</p>
 *
 * <p><b>为什么检查与启动要能在同一台机器上共存</b>：端口检测天然幂等 ——
 * 服务已在跑就直接跳过。这一点很重要，因为 {@code nginx.exe} 重复执行 start
 * 会报 {@code [emerg] bind() failed} 并在日志里留一堆脏记录，而 Redis 重复启动
 * 可能抢占同一个 dump.rdb。宁可少做一步，也不要做出破坏性动作。</p>
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
     * 检测到服务未运行时是否尝试自动启动。
     *
     * <p>默认 false，理由见类注释：Nginx 需要管理员权限，不该由项目代劳。
     * 开启后仍需以管理员身份运行项目，否则启动会因权限不足而失败，
     * 日志里会给出明确提示。</p>
     */
    private boolean autoStart = false;

    /**
     * 检查失败（服务不可用且未自动启动）时是否阻止应用启动。
     *
     * <p>默认 false：Nginx 只影响静态资源预览，页面主体仍可正常使用，
     * 因为一个可选依赖没起就把整个项目拦下来太重了。想要严格一些的可以改成 true。</p>
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
     * Redis 配置。host / port 刻意与 spring.data.redis 分开写 ——
     * 后者从环境变量注入且可能是远端地址，而这里要探的是**本机**服务，
     * 复用同一个值会在「Redis 配到远端」时得出错误结论。
     */
    @Data
    public static class Redis {

        private boolean enabled = true;

        private String host = "127.0.0.1";

        private int port = 6379;

        /**
         * 服务不可用时提示用户执行的命令。
         *
         * <p>写成配置而不是硬编码在代码里：安装路径因机器而异，
         * 换台电脑不该改代码。</p>
         */
        private String startCommand = "D:\\Redis\\start-redis.bat";
    }

    /**
     * Nginx 配置。
     */
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

    /**
     * Redis 检查是否生效。
     */
    public boolean isRedisCheckActive() {
        return enabled && redis.isEnabled();
    }

    /**
     * Nginx 检查是否生效。
     */
    public boolean isNginxCheckActive() {
        return enabled && nginx.isEnabled();
    }
}

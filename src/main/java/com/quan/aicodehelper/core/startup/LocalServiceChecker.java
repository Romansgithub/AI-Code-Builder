package com.quan.aicodehelper.core.startup;

import com.quan.aicodehelper.config.LocalServiceProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 启动时自检本地依赖服务（Nginx / Redis），缺失则给出可复制的启动命令。
 *
 * <p><b>为什么用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}</b>：
 * 检查发生在依赖注入之后、且晚于 Spring 上下文刷新，不会拖慢关键路径，也不会
 * 因为一个可选依赖没起就导致 bean 创建失败。放在 {@code @PostConstruct} 里做
 * 这类带超时的外部探测，等于给启动流程埋一个不确定的卡顿点。</p>
 *
 * <p><b>为什么在独立线程里跑</b>：探测本身有超时（默认单个服务最多等 15 秒），
 * 两个服务串起来最坏 30 秒。虽然事件监听是在 main 线程上执行的，但「就绪」之后
 * 用户已经在 IDE 里看到启动完成了，这时候让 main 线程阻塞着没有意义。
 * 更重要的是：{@code ApplicationReadyEvent} 是同步派发的，在这里阻塞会推迟
 * 「应用已就绪」的完成信号 —— 独立线程既让日志清晰，也不干扰启动时序。</p>
 *
 * <p><b>检查是幂等的</b>：只看端口是否在监听，服务已在跑就直接跳过。
 * 绝不重复执行 start —— {@code nginx.exe} 重复 start 会报
 * {@code [emerg] bind() failed} 并污染日志，Redis 重复启动可能抢占 dump.rdb。</p>
 *
 * <p><b>为什么是 TCP 探测而不是进程名匹配</b>：进程名匹配会把「起在别处、监听
 * 别的端口」的实例误判为就绪，也会被同名进程干扰；而端口可连才是这个服务
 * 对项目真正有意义的状态 —— 毕竟 Nginx 是给静态资源反代用的。</p>
 *
 * <p><b>为什么默认不自动启动</b>：Nginx 绑 80 端口需要管理员权限，详见
 * {@link LocalServiceProperties} 类注释。默认只检测并提示。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalServiceChecker {

    private final LocalServiceProperties props;

    /**
     * 探测线程。持有引用是为了在应用关闭时能及时收尾，避免留下游离线程。
     */
    private volatile Thread probeThread;

    /**
     * 关闭时中断探测：应用都要关了，没必要再等一个不会启动的外部服务。
     */
    @PreDestroy
    public void shutdown() {
        Thread t = this.probeThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /**
     * 应用就绪后触发自检。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!props.isEnabled()) {
            log.info("本地服务自检已关闭（app.local-services.enabled=false）");
            return;
        }
        Thread t = new Thread(this::checkAll, "local-service-checker");
        t.setDaemon(true);
        this.probeThread = t;
        t.start();
    }

    /**
     * 依次检查 Redis 与 Nginx。
     *
     * <p>刻意不并发：两个探测各自有超时，串行最多多等一个超时的时长，
     * 但日志顺序确定、排查时不必分辨交错输出。这是启动路径，不差这点时间。</p>
     */
    void checkAll() {
        ServiceCheck redis = check(
                "Redis",
                props.getRedis().getHost(),
                props.getRedis().getPort(),
                props.getRedis().getStartCommand(),
                props.getRedis().isEnabled());

        ServiceCheck nginx = check(
                "Nginx",
                props.getNginx().getHost(),
                props.getNginx().getPort(),
                props.getNginx().getStartCommand(),
                props.getNginx().isEnabled());

        // 汇总成一块，而不是零散地撒在启动日志里 —— 启动日志本来就很长，
        // 一条分散的 WARN 极易被忽略，那这个检查就等于没做。
        logSummary(redis, nginx);
    }

    /**
     * 检查单个服务。
     *
     * <p>注意每个分支都必须走到底并返回状态 —— 不提前 return，这样汇总时才
     * 能拿到全部信息，不会因为 Redis 先挂了就漏报 Nginx。</p>
     */
    private ServiceCheck check(String name, String host, int port,
                               String startCommand, boolean enabled) {
        if (!props.isEnabled() || !enabled) {
            return ServiceCheck.skipped(name, host, port);
        }
        if (isPortListening(host, port)) {
            log.info("[本地依赖] {} 已就绪（{}:{}）", name, host, port);
            return ServiceCheck.ready(name, host, port);
        }

        // 未就绪。先看看配置的启动命令是否存在 —— 命令文件不存在是配置问题，
        // 要单独报，混在一起报会把「路径配错了」误传成「服务没起」。
        boolean commandAvailable = startCommandAvailable(startCommand);

        if (props.isAutoStart()) {
            log.warn("[本地依赖] {} 未就绪（{}:{}），尝试自动启动…", name, host, port);
            if (tryAutoStart(name, startCommand, commandAvailable)
                    && waitUntilReady(name, host, port)) {
                return ServiceCheck.ready(name, host, port);
            }
            // 自动启动失败最常见的原因就是权限不足（Nginx 绑 80 端口），
            // 这里必须把这一点讲清楚，否则用户会以为是路径或配置写错了。
            return ServiceCheck.failed(name, host, port, startCommand, commandAvailable,
                    "自动启动失败（若为 Nginx 且监听 80 端口，通常是权限不足 —— "
                            + "请以管理员身份运行项目，或关闭 auto-start 改为手动启动）");
        }

        return ServiceCheck.failed(name, host, port, startCommand, commandAvailable, null);
    }

    /**
     * 判定端口是否可连接。
     *
     * <p>连接超时给 1 秒：探测的是本机端口，正常的拒绝几乎是瞬时的；
     * 需要等到超时的情况基本可以视为该端口上没人在听。</p>
     */
    boolean isPortListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 等待服务就绪。
     *
     * <p>必须有超时上限：npm / 服务启动是异步的，无限等待会把探测线程
     * 永久挂住，日志里留下一条没有结论的记录。</p>
     */
    private boolean waitUntilReady(String name, String host, int port) {
        long deadline = System.currentTimeMillis() + props.getReadyTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[本地依赖] {} 就绪等待被中断（应用正在关闭）", name);
                return false;
            }
            if (isPortListening(host, port)) {
                log.info("[本地依赖] {} 自动启动成功（{}:{}）", name, host, port);
                return true;
            }
            try {
                Thread.sleep(props.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[本地依赖] {} 就绪等待被中断（应用正在关闭）", name);
                return false;
            }
        }
        log.warn("[本地依赖] {} 等待 {}ms 后仍未就绪", name, props.getReadyTimeoutMs());
        return false;
    }

    /**
     * 尝试执行配置的启动命令。
     *
     * <p>命令一律作为**独立进程**启动（{@code ProcessBuilder} 不等待其结束），
     * 因为 Redis 与 Nginx 都是常驻进程，等待它们退出等于永远阻塞。
     * 它们的标准输出也不接管 —— 接管了就要自己读，不读又会因管道缓冲区
     * 写满而把子进程卡死，是个很容易踩的坑。</p>
     */
    private boolean tryAutoStart(String name, String startCommand, boolean commandAvailable) {
        if (!commandAvailable) {
            log.warn("[本地依赖] {} 的启动命令不存在：{}", name, startCommand);
            return false;
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", startCommand)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            log.warn("[本地依赖] {} 启动命令执行失败：{}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 启动命令文件是否存在。
     *
     * <p>既认绝对路径也认 PATH 上的命令 —— 用户可能填一个全局脚本名。</p>
     */
    boolean startCommandAvailable(String startCommand) {
        if (startCommand == null || startCommand.isBlank()) {
            return false;
        }
        java.io.File file = new java.io.File(startCommand);
        if (file.isAbsolute()) {
            return file.isFile();
        }
        // 相对路径按项目工作目录解析
        if (file.isFile()) {
            return true;
        }
        return file.exists();
    }

    /**
     * 输出汇总。
     *
     * <p>全绿时用 INFO 一行带过（不打扰），有缺失时用 WARN 并给出可直接
     * 复制粘贴的命令 —— 提示的价值全在「可执行」上，只说「Redis 没启动」
     * 等于把排查工作又丢回给用户。</p>
     */
    private void logSummary(ServiceCheck redis, ServiceCheck nginx) {
        if (!redis.isProblem() && !nginx.isProblem()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌─ 本地依赖自检未通过 ────────────────────────────────────\n");
        appendLine(sb, redis);
        appendLine(sb, nginx);
        sb.append("└───────────────────────────────────────────────────────\n");
        sb.append("提示：这两项只影响静态资源预览与会话保持，不阻塞本次启动。\n");
        sb.append("      排查顺序建议：先看下面路径下的日志，再确认端口是否被别的进程占用。\n");
        log.warn(sb.toString());
    }

    private void appendLine(StringBuilder sb, ServiceCheck check) {
        if (check.skipped()) {
            return;
        }
        if (check.ready()) {
            sb.append("│ [OK]  ").append(check.name())
                    .append("（").append(check.host()).append(':').append(check.port()).append("）\n");
            return;
        }
        sb.append("│ [缺失] ").append(check.name())
                .append(" → ").append(check.host()).append(':').append(check.port())
                .append(" 无法连接\n");
        if (check.detail() != null) {
            sb.append("│        ").append(check.detail()).append("\n");
        }
        if (check.commandAvailable()) {
            sb.append("│        启动命令：").append(check.startCommand()).append("\n");
        } else {
            sb.append("│        启动命令不存在：").append(check.startCommand()).append("\n");
            sb.append("│        请在 app.local-services 下修正路径，或手动启动后再启动本项目\n");
        }
    }

    /**
     * 单个服务的检查结果。
     *
     * <p>用 record 而不是 boolean，是为了区分「跳过检查」「已就绪」「缺失」三态 ——
     * 只用一个布尔值的话，「没检查」和「检查通过」会混为一谈，
     * 汇总时就分不清该不该提示。</p>
     */
    record ServiceCheck(String name, String host, int port,
                        boolean skipped, boolean ready, String startCommand,
                        boolean commandAvailable, String detail) {

        static ServiceCheck skipped(String name, String host, int port) {
            return new ServiceCheck(name, host, port, true, true, null, false, null);
        }

        static ServiceCheck ready(String name, String host, int port) {
            return new ServiceCheck(name, host, port, false, true, null, false, null);
        }

        static ServiceCheck failed(String name, String host, int port,
                                   String startCommand, boolean commandAvailable, String detail) {
            return new ServiceCheck(name, host, port, false, false,
                    startCommand, commandAvailable, detail);
        }

        /**
         * 是否存在问题（缺失且没有跳过）。
         */
        boolean isProblem() {
            return !skipped && !ready;
        }
    }
}

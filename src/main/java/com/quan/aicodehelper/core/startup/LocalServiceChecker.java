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
 * 监听 {@code ApplicationReadyEvent} 并在独立守护线程跑，避免阻塞「应用已就绪」信号；
 * 只看端口是否可连，已跑就跳过（进程名匹配会误判起在别处的实例，重复 start 会 bind 失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalServiceChecker {

    private final LocalServiceProperties props;

    /** 探测线程。持有引用是为了在应用关闭时能及时收尾，避免留下游离线程。 */
    private volatile Thread probeThread;

    @PreDestroy
    public void shutdown() {
        Thread t = this.probeThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

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
     * 依次检查 Redis 与 Nginx，刻意不并发：串行最多多等一个超时，但日志顺序确定、无需分辨交错输出。
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

        // 汇总成一块而非零散撒在启动日志里：启动日志很长，分散的 WARN 极易被忽略，检查等于没做。
        logSummary(redis, nginx);
    }

    /** 每个分支都必须走到底并返回状态，不提前 return，否则汇总时可能因 Redis 先挂而漏报 Nginx。 */
    private ServiceCheck check(String name, String host, int port,
                               String startCommand, boolean enabled) {
        if (!props.isEnabled() || !enabled) {
            return ServiceCheck.skipped(name, host, port);
        }
        if (isPortListening(host, port)) {
            log.info("[本地依赖] {} 已就绪（{}:{}）", name, host, port);
            return ServiceCheck.ready(name, host, port);
        }

        // 先确认启动命令是否存在：命令文件缺失是配置问题，要单独报，否则会把「路径配错」误传成「服务没起」。
        boolean commandAvailable = startCommandAvailable(startCommand);

        if (props.isAutoStart()) {
            log.warn("[本地依赖] {} 未就绪（{}:{}），尝试自动启动…", name, host, port);
            if (tryAutoStart(name, startCommand, commandAvailable)
                    && waitUntilReady(name, host, port)) {
                return ServiceCheck.ready(name, host, port);
            }
            // 自动启动失败最常见原因是权限不足（Nginx 绑 80 端口），必须点明，否则用户会误以为是路径或配置错误。
            return ServiceCheck.failed(name, host, port, startCommand, commandAvailable,
                    "自动启动失败（若为 Nginx 且监听 80 端口，通常是权限不足 —— "
                            + "请以管理员身份运行项目，或关闭 auto-start 改为手动启动）");
        }

        return ServiceCheck.failed(name, host, port, startCommand, commandAvailable, null);
    }

    /** 连接超时 1 秒：探测本机端口时正常拒绝几乎瞬时，等到超时基本可视为无人在听。 */
    boolean isPortListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 必须有超时上限：服务启动异步，无限等待会把探测线程永久挂住，留下无结论的日志。 */
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

    /** 不等待其结束（常驻进程等退出等于永久阻塞），也不接管标准输出，否则管道缓冲写满会卡死子进程。 */
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

    /** 既认绝对路径也认 PATH 上的命令（用户可能填全局脚本名）。 */
    boolean startCommandAvailable(String startCommand) {
        if (startCommand == null || startCommand.isBlank()) {
            return false;
        }
        java.io.File file = new java.io.File(startCommand);
        if (file.isAbsolute()) {
            return file.isFile();
        }
        if (file.isFile()) {
            return true;
        }
        return file.exists();
    }

    /** 只为有缺失的服务输出，提示价值全在「可直接复制的命令」上。 */
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

    /** 区分「跳过 / 已就绪 / 缺失」三态，否则「没检查」与「检查通过」会混为一谈。 */
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

        boolean isProblem() {
            return !skipped && !ready;
        }
    }
}

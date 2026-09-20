package com.quan.aicodehelper.core.startup;

import com.quan.aicodehelper.config.LocalServiceProperties;
import com.quan.aicodehelper.core.startup.LocalServiceChecker.ServiceCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地依赖自检测试。
 *
 * <p><b>为什么必须用真端口而不是 mock 掉探测</b>：这个类的价值全在「端口能否
 * 连上」这一个判断上。若把 {@code isPortListening} mock 掉，测试就只是在验证
 * 自己的 if/else，真实的连接超时、IPv4 绑定、端口占用这些行为一个都测不到。
 * 所以这里用 {@link ServerSocket} 真起一个监听来代表「服务已就绪」，
 * 端口填 0 让操作系统分配 —— 避免固定端口在别的机器上恰好被占用导致假失败。</p>
 *
 * <p><b>为什么启动命令要指向临时文件</b>：默认配置里的
 * {@code D:\Redis\start-redis.bat} 是开发机的路径，写死在测试里换台机器就红。
 * 所以每个用例都自己造临时脚本（或指向一个不存在的路径），
 * 只验证「命令是否存在」这一层判断。</p>
 */
@DisplayName("本地依赖自检")
class LocalServiceCheckerTest {

    private LocalServiceProperties props;
    private LocalServiceChecker checker;

    @BeforeEach
    void setUp() {
        props = new LocalServiceProperties();
        props.init();
        checker = new LocalServiceChecker(props);
    }

    /**
     * 起一个真监听，返回端口号。用 try-with-resources 关掉即可释放。
     */
    private static ServerSocket listeningSocket() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        socket.setReuseAddress(true);
        return socket;
    }

    /**
     * 造一个临时的「启动脚本」文件，避免依赖开发机上的真实路径。
     */
    private static String tempScript(String name) throws IOException {
        Path dir = Files.createTempDirectory("local-svc-test");
        Path file = dir.resolve(name);
        Files.writeString(file, "@echo off\r\nexit /b 0\r\n");
        file.toFile().deleteOnExit();
        return file.toString();
    }

    @Nested
    @DisplayName("端口探测")
    class PortProbe {

        @Test
        @DisplayName("端口上有服务在听应判定为已就绪")
        void shouldDetectListeningPort() throws IOException {
            try (ServerSocket socket = listeningSocket()) {
                assertThat(checker.isPortListening("127.0.0.1", socket.getLocalPort()))
                        .as("有 ServerSocket 在监听，必须判定为可连接")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("端口上没人在听应判定为不可连接（而不是抛异常）")
        void shouldDetectClosedPort() throws IOException {
            int port;
            try (ServerSocket socket = listeningSocket()) {
                port = socket.getLocalPort();
            }
            // 上面已关闭，这个端口此刻无人监听。
            // 关键：返回 false 而不是抛异常 —— 探测的可预期性比速度重要。
            assertThat(checker.isPortListening("127.0.0.1", port)).isFalse();
        }

        @Test
        @DisplayName("连接失败要快速返回，不能一直挂着")
        void shouldFailFastOnUnreachablePort() {
            // 用不可路由地址，逼出超时路径
            long startedAt = System.currentTimeMillis();
            boolean result = checker.isPortListening("10.255.255.1", 6379);
            long cost = System.currentTimeMillis() - startedAt;

            assertThat(result).isFalse();
            // 超时设的是 1000ms；给足余量但要确保不是无限等
            assertThat(cost)
                    .as("探测必须有上限，否则启动自检会把线程永久挂住")
                    .isLessThan(5000L);
        }
    }

    @Nested
    @DisplayName("已就绪时应跳过")
    class AlreadyRunning {

        @Test
        @DisplayName("服务已就绪时探测结果应为 true")
        void shouldMarkReady() throws IOException {
            try (ServerSocket socket = listeningSocket()) {
                assertThat(checker.isPortListening("127.0.0.1", socket.getLocalPort())).isTrue();
            }
        }

        @Test
        @DisplayName("重复启动是有害的：已就绪时必须跳过，不能再去执行启动命令")
        void shouldNotStartWhenAlreadyReady() throws IOException {
            // 造一个「一旦被执行就会留下痕迹」的脚本，用它证明没被执行。
            // 比 mock 更强：直接验证真实的进程启动路径没被走到。
            Path marker = Files.createTempDirectory("local-svc-marker").resolve("started.txt");
            String script = tempScript("loop.bat");
            Files.writeString(Path.of(script),
                    "@echo off\r\necho started > \"" + marker + "\"\r\n");

            try (ServerSocket socket = listeningSocket()) {
                props.getRedis().setPort(socket.getLocalPort());
                props.setAutoStart(true);
                props.getRedis().setStartCommand(script);
                props.getNginx().setEnabled(false);

                checker.checkAll();
                // 给一点时间：若真去执行了脚本，痕迹会在这段时间里出现
                sleepQuietly(600);
            }

            assertThat(Files.exists(marker))
                    .as("nginx.exe 重复 start 会报 bind() failed、Redis 重复启动会抢 dump.rdb，"
                            + "所以已就绪时必须跳过")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("缺失时的处理")
    class Missing {

        /**
         * 取一个确定无人监听的端口。
         */
        private int freePort() throws IOException {
            try (ServerSocket socket = listeningSocket()) {
                return socket.getLocalPort();
            }
        }

        @Test
        @DisplayName("默认（不自动启动）时只报告，不执行启动命令")
        void shouldOnlyReportWhenAutoStartDisabled() throws IOException {
            Path marker = Files.createTempDirectory("local-svc-2").resolve("started.txt");
            String script = tempScript("loop2.bat");
            Files.writeString(Path.of(script),
                    "@echo off\r\necho started > \"" + marker + "\"\r\n");

            props.setAutoStart(false);
            props.getRedis().setPort(freePort());
            props.getRedis().setStartCommand(script);
            props.getNginx().setEnabled(false);

            checker.checkAll();
            sleepQuietly(600);

            assertThat(Files.exists(marker))
                    .as("默认策略是「检测 + 提示」，不该有副作用")
                    .isFalse();
        }

        @Test
        @DisplayName("自动启动开启时会执行启动命令")
        void shouldRunCommandWhenAutoStartEnabled() throws IOException {
            Path marker = Files.createTempDirectory("local-svc-3").resolve("started.txt");
            String script = tempScript("loop3.bat");
            Files.writeString(Path.of(script),
                    "@echo off\r\necho started > \"" + marker + "\"\r\n");

            props.setAutoStart(true);
            props.setReadyTimeoutMs(1200);
            props.setPollIntervalMs(200);
            props.getRedis().setPort(freePort());
            props.getRedis().setStartCommand(script);
            props.getNginx().setEnabled(false);

            checker.checkAll();

            // 脚本是异步拉起的，轮询等它把痕迹写出来
            boolean appeared = false;
            for (int i = 0; i < 20 && !appeared; i++) {
                sleepQuietly(200);
                appeared = Files.exists(marker);
            }
            assertThat(appeared)
                    .as("auto-start=true 时应真的去拉起服务")
                    .isTrue();
        }

        @Test
        @DisplayName("启动命令文件不存在时要明确提示路径有误，而不是静默无输出")
        void shouldReportMissingCommandFile() throws IOException {
            props.setAutoStart(true);
            props.setReadyTimeoutMs(400);
            props.setPollIntervalMs(100);
            props.getRedis().setPort(freePort());
            props.getRedis().setStartCommand("D:/definitely/not/here/start.bat");
            props.getNginx().setEnabled(false);

            // 不应抛异常，只是把「命令不存在」如实报出来
            checker.checkAll();

            assertThat(checker.startCommandAvailable("D:/definitely/not/here/start.bat"))
                    .isFalse();
        }

        @Test
        @DisplayName("启动命令为真实文件时 startCommandAvailable 应为 true")
        void shouldDetectExistingCommandFile() throws IOException {
            assertThat(checker.startCommandAvailable(tempScript("ok.bat"))).isTrue();
            assertThat(checker.startCommandAvailable("")).isFalse();
            assertThat(checker.startCommandAvailable(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("总开关与前置校验")
    class Guards {

        @Test
        @DisplayName("总开关关闭时不探测任何端口")
        void shouldSkipWhenDisabled() throws IOException {
            try (ServerSocket socket = listeningSocket()) {
                props.setEnabled(false);
                // 即使端口是通的，也不该去探 —— 关掉开关就是完全不介入
                props.getRedis().setPort(socket.getLocalPort());
                assertThat(props.isRedisCheckActive()).isFalse();
                checker.checkAll();
            }
            assertThat(props.isNginxCheckActive()).isFalse();
        }

        @Test
        @DisplayName("单服务开关关闭时该项不生效")
        void shouldRespectPerServiceToggle() throws IOException {
            props.getRedis().setEnabled(false);
            assertThat(props.isRedisCheckActive()).isFalse();
            assertThat(props.isNginxCheckActive()).isTrue();
        }

        @Test
        @DisplayName("非法端口应被重置为默认值，避免探测到无意义的目标")
        void shouldResetInvalidPorts() {
            props.getRedis().setPort(0);
            props.getNginx().setPort(70000);
            props.init();

            assertThat(props.getRedis().getPort()).isEqualTo(6379);
            assertThat(props.getNginx().getPort()).isEqualTo(80);
        }

        @Test
        @DisplayName("非法超时值应被重置，避免就绪等待失去上限")
        void shouldResetInvalidTimeouts() {
            props.setReadyTimeoutMs(0);
            props.setPollIntervalMs(-5);
            props.init();

            assertThat(props.getReadyTimeoutMs()).isPositive();
            assertThat(props.getPollIntervalMs()).isPositive();
        }

        @Test
        @DisplayName("默认策略必须是「只检查不启动」—— 自动启动需管理员权限")
        void shouldDefaultToReportOnly() {
            LocalServiceProperties defaults = new LocalServiceProperties();
            defaults.init();

            assertThat(defaults.isEnabled()).as("默认开启检查").isTrue();
            assertThat(defaults.isAutoStart())
                    .as("Nginx 绑 80 端口需要管理员权限，不该由项目擅自提权")
                    .isFalse();
            assertThat(defaults.isFailFast())
                    .as("为可选依赖拦截整个启动过重")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("三态结果")
    class ServiceCheckState {

        @Test
        @DisplayName("跳过检查不能等同于「有问题」")
        void skippedIsNotProblem() {
            ServiceCheck skipped = ServiceCheck.skipped("Redis", "127.0.0.1", 6379);

            assertThat(skipped.skipped()).isTrue();
            assertThat(skipped.isProblem())
                    .as("只用一个布尔值的话，「没检查」会被误报成「缺失」")
                    .isFalse();
        }

        @Test
        @DisplayName("已就绪不算问题")
        void readyIsNotProblem() {
            assertThat(ServiceCheck.ready("Nginx", "127.0.0.1", 80).isProblem()).isFalse();
        }

        @Test
        @DisplayName("缺失才算问题")
        void failedIsProblem() {
            ServiceCheck failed = ServiceCheck.failed("Nginx", "127.0.0.1", 80,
                    "D:/nginx/start.bat", true, null);

            assertThat(failed.isProblem()).isTrue();
            assertThat(failed.commandAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("生命周期")
    class Lifecycle {

        @Test
        @DisplayName("应用关闭时应能中断正在进行的就绪等待")
        void shouldInterruptProbeOnShutdown() {
            props.setAutoStart(true);
            props.setReadyTimeoutMs(30_000);
            props.setPollIntervalMs(200);
            props.getRedis().setPort(1);
            props.getRedis().setStartCommand("");
            props.getNginx().setEnabled(false);

            AtomicReference<Thread> probe = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                probe.set(Thread.currentThread());
                checker.checkAll();
            });
            runner.setDaemon(true);

            // 用反射把探测线程塞进去，再走真实的 shutdown 路径
            runner.start();
            waitUntil(() -> probe.get() != null);
            ReflectionTestUtils.setField(checker, "probeThread", probe.get());
            checker.shutdown();

            // 中断后 checkAll 顶多再转一圈就该退出
            waitUntil(() -> !runner.isAlive());
            assertThat(runner.isAlive())
                    .as("应用都要关了，不该还挂着等一个不会启动的服务")
                    .isFalse();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 50; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(100);
        }
    }
}

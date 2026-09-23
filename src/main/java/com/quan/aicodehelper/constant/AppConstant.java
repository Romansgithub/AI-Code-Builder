package com.quan.aicodehelper.constant;

import java.nio.file.Paths;

/**
 * 全局常量。
 *
 * <p>路径与部署地址由 {@link com.quan.aicodehelper.config.StorageProperties} 启动时回填，
 * 调用方一律走 {@link #getCodeOutputRootDir()} 等 getter，不要直接引用下面的占位字段。</p>
 */
public interface AppConstant {

    /** 代码生成产物根目录的兜底默认值（按 JVM 工作目录推算），请用 {@link #getCodeOutputRootDir()}。 */
    @Deprecated
    String DEFAULT_CODE_OUTPUT_ROOT_DIR =
            Paths.get(System.getProperty("user.dir"), "tmp", "code_output").toAbsolutePath().normalize().toString();

    /** 部署产物根目录的兜底默认值，请用 {@link #getCodeDeployRootDir()}。 */
    @Deprecated
    String DEFAULT_CODE_DEPLOY_ROOT_DIR =
            Paths.get(System.getProperty("user.dir"), "tmp", "code_deploy").toAbsolutePath().normalize().toString();

    /** 部署访问地址前缀的兜底默认值，请用 {@link #getCodeDeployHost()}。 */
    @Deprecated
    String DEFAULT_CODE_DEPLOY_HOST = "http://localhost:8080/api/static/";

    Integer GOOD_APP_PRIORITY = 99;

    Integer DEFAULT_APP_PRIORITY = 0;

    /**
     * 运行时配置持有者：接口字段隐含 {@code public static final} 无法赋值，故把可变状态收进嵌套类。
     */
    final class Storage {
        private Storage() {
        }

        private static volatile String codeOutputRootDir = DEFAULT_CODE_OUTPUT_ROOT_DIR;
        private static volatile String codeDeployRootDir = DEFAULT_CODE_DEPLOY_ROOT_DIR;
        private static volatile String codeDeployHost = DEFAULT_CODE_DEPLOY_HOST;

        /** 由 StorageProperties 启动时回填真实配置。 */
        public static void apply(String outputDir, String deployDir, String deployHost) {
            if (outputDir != null && !outputDir.isBlank()) {
                codeOutputRootDir = outputDir;
            }
            if (deployDir != null && !deployDir.isBlank()) {
                codeDeployRootDir = deployDir;
            }
            if (deployHost != null && !deployHost.isBlank()) {
                codeDeployHost = deployHost.endsWith("/") ? deployHost : deployHost + "/";
            }
        }
    }

    static String getCodeOutputRootDir() {
        return Storage.codeOutputRootDir;
    }

    static String getCodeDeployRootDir() {
        return Storage.codeDeployRootDir;
    }

    /** 部署访问地址前缀，保证以 "/" 结尾。 */
    static String getCodeDeployHost() {
        return Storage.codeDeployHost;
    }
}

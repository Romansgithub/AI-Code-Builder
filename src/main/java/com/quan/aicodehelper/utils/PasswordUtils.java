package com.quan.aicodehelper.utils;

import cn.hutool.core.util.StrUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 密码加密与校验工具：新密码用 BCrypt，同时兼容存量用户的旧 MD5 格式。
 * MD5 分支不能删：直接切 BCrypt 会让存量用户的 MD5 密码全部无法登录；校验先按 BCrypt，失败回退 MD5，
 * 通过后由业务层调 {@link #needsUpgrade} 重写为 BCrypt。
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Component
public class PasswordUtils {

    /**
     * 旧数据的 MD5 盐值。
     *
     * @deprecated 仅用于校验历史 MD5 密码，存量迁移完成后连同 {@link #matchesLegacyMd5} 一并删除。
     */
    @Deprecated
    private static final String LEGACY_SALT = "quan";

    /** BCrypt 强度：10 为默认值，兼顾安全与性能。 */
    private static final int BCRYPT_STRENGTH = 10;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /** 加密为 BCrypt 哈希，空密码抛 IllegalArgumentException。 */
    public String encode(String rawPassword) {
        if (StrUtil.isBlank(rawPassword)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验密码，兼容 BCrypt 与历史遗留的 MD5 两种格式。
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (StrUtil.isBlank(rawPassword) || StrUtil.isBlank(storedPassword)) {
            return false;
        }
        if (isBcryptHash(storedPassword)) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        return matchesLegacyMd5(rawPassword, storedPassword);
    }

    /**
     * 登录成功后是否需要把该密码重写为 BCrypt：存储值不是 BCrypt 哈希时为 true。
     */
    public boolean needsUpgrade(String storedPassword) {
        return StrUtil.isNotBlank(storedPassword) && !isBcryptHash(storedPassword);
    }

    /**
     * 是否为 BCrypt 哈希：形如 {@code $2a$10$...}，固定以 {@code $2} 开头、总长 60。
     */
    private boolean isBcryptHash(String password) {
        return password.length() == 60 && password.startsWith("$2");
    }

    /**
     * 按历史 MD5 规则校验密码。
     *
     * @deprecated 仅用于兼容存量数据，迁移完成后应删除
     */
    @Deprecated
    private boolean matchesLegacyMd5(String rawPassword, String storedPassword) {
        String md5 = DigestUtils.md5DigestAsHex(
                (LEGACY_SALT + rawPassword).getBytes(StandardCharsets.UTF_8));
        return md5.equalsIgnoreCase(storedPassword);
    }
}

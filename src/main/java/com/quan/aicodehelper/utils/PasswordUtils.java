package com.quan.aicodehelper.utils;

import cn.hutool.core.util.StrUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 密码加密与校验工具。
 *
 * <p><b>历史背景</b>：项目早期使用 {@code MD5 + 固定盐 "quan"} 存储密码。
 * MD5 速度快、盐值硬编码，一旦库被拖走可被彩虹表快速破解，属于必须修复的安全缺陷。
 * 现统一迁移至 BCrypt。</p>
 *
 * <p><b>渐进式迁移策略</b>：直接切换会导致存量用户的 MD5 密码全部无法登录。
 * 因此这里保留对旧格式的识别与校验能力：
 * <ol>
 *   <li>新注册 / 改密的用户，直接写入 BCrypt 哈希；</li>
 *   <li>老用户登录时，{@link #matches} 会先按 BCrypt 校验，失败再回退按 MD5 校验；</li>
 *   <li>校验通过后由业务层调用 {@link #needsUpgrade} 判断是否需要把密码重写为 BCrypt，
 *       实现「用户无感知的一次性升级」。</li>
 * </ol>
 * 待存量密码全部升级完毕后，可移除 MD5 兼容代码。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Component
public class PasswordUtils {

    /**
     * 兼容旧数据的 MD5 盐值。
     *
     * @deprecated 仅用于校验历史遗留的 MD5 密码，新密码一律使用 BCrypt。
     *             存量数据迁移完成后应连同 {@link #matchesLegacyMd5} 一并删除。
     */
    @Deprecated
    private static final String LEGACY_SALT = "quan";

    /** BCrypt 强度：10 为默认值，兼顾安全与性能 */
    private static final int BCRYPT_STRENGTH = 10;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /**
     * 加密密码（BCrypt）。
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希值
     */
    public String encode(String rawPassword) {
        if (StrUtil.isBlank(rawPassword)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验密码，同时兼容 BCrypt 与历史遗留的 MD5 格式。
     *
     * @param rawPassword     明文密码
     * @param storedPassword  数据库中存储的密码
     * @return 是否匹配
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (StrUtil.isBlank(rawPassword) || StrUtil.isBlank(storedPassword)) {
            return false;
        }
        // 1. 优先按 BCrypt 校验（新格式）
        if (isBcryptHash(storedPassword)) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        // 2. 回退按 MD5 校验（历史遗留数据）
        return matchesLegacyMd5(rawPassword, storedPassword);
    }

    /**
     * 判断该密码是否需要在登录成功后升级为 BCrypt。
     *
     * @param storedPassword 数据库中存储的密码
     * @return true 表示仍是旧的 MD5 格式，需要升级
     */
    public boolean needsUpgrade(String storedPassword) {
        return StrUtil.isNotBlank(storedPassword) && !isBcryptHash(storedPassword);
    }

    /**
     * 判断是否为 BCrypt 哈希。
     *
     * <p>BCrypt 哈希形如 {@code $2a$10$...}，固定以 {@code $2} 开头、总长 60。</p>
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

package com.quan.aicodehelper.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码工具测试。
 *
 * <p>重点覆盖三件事：
 * <ol>
 *   <li>BCrypt 加密与校验的基本正确性；</li>
 *   <li>对历史遗留 MD5 密码的向后兼容（保证存量用户仍能登录）；</li>
 *   <li>升级判定逻辑，确保旧格式能被识别并触发迁移。</li>
 * </ol>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@DisplayName("密码工具 PasswordUtils")
class PasswordUtilsTest {

    private final PasswordUtils passwordUtils = new PasswordUtils();

    /** 按历史规则生成 MD5 密码，用于模拟存量数据 */
    private String legacyMd5(String rawPassword) {
        return DigestUtils.md5DigestAsHex(("quan" + rawPassword).getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("BCrypt 加密")
    class BcryptEncode {

        @Test
        @DisplayName("加密结果应为 BCrypt 格式（$2 开头、长度 60）")
        void shouldProduceBcryptHash() {
            String hash = passwordUtils.encode("myPassword123");
            assertThat(hash).startsWith("$2").hasSize(60);
        }

        @Test
        @DisplayName("相同明文每次加密结果应不同（加盐）")
        void shouldProduceDifferentHashEachTime() {
            String first = passwordUtils.encode("myPassword123");
            String second = passwordUtils.encode("myPassword123");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("明文不应原样出现在哈希中")
        void shouldNotContainRawPassword() {
            String hash = passwordUtils.encode("myPassword123");
            assertThat(hash).doesNotContain("myPassword123");
        }

        @Test
        @DisplayName("空密码应抛出异常")
        void shouldRejectBlankPassword() {
            assertThatThrownBy(() -> passwordUtils.encode(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> passwordUtils.encode(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("密码校验")
    class Matches {

        @Test
        @DisplayName("BCrypt 密码：正确明文通过，错误明文拒绝")
        void shouldVerifyBcryptPassword() {
            String hash = passwordUtils.encode("correctPassword");
            assertThat(passwordUtils.matches("correctPassword", hash)).isTrue();
            assertThat(passwordUtils.matches("wrongPassword", hash)).isFalse();
        }

        @Test
        @DisplayName("MD5 历史密码：正确明文应能通过（保证存量用户可登录）")
        void shouldVerifyLegacyMd5Password() {
            String legacyHash = legacyMd5("oldUserPassword");
            assertThat(passwordUtils.matches("oldUserPassword", legacyHash)).isTrue();
        }

        @Test
        @DisplayName("MD5 历史密码：错误明文应被拒绝")
        void shouldRejectWrongLegacyPassword() {
            String legacyHash = legacyMd5("oldUserPassword");
            assertThat(passwordUtils.matches("wrongPassword", legacyHash)).isFalse();
        }

        @Test
        @DisplayName("MD5 大小写不影响校验结果")
        void shouldBeCaseInsensitiveForLegacyMd5() {
            String legacyHash = legacyMd5("oldUserPassword").toUpperCase();
            assertThat(passwordUtils.matches("oldUserPassword", legacyHash)).isTrue();
        }

        @Test
        @DisplayName("空值输入应安全返回 false，不抛异常")
        void shouldReturnFalseForBlankInput() {
            assertThat(passwordUtils.matches(null, "anyHash")).isFalse();
            assertThat(passwordUtils.matches("anyPassword", null)).isFalse();
            assertThat(passwordUtils.matches("", "")).isFalse();
        }
    }

    @Nested
    @DisplayName("升级判定")
    class NeedsUpgrade {

        @Test
        @DisplayName("MD5 格式应被判定为需要升级")
        void shouldMarkLegacyAsNeedingUpgrade() {
            assertThat(passwordUtils.needsUpgrade(legacyMd5("somePassword"))).isTrue();
        }

        @Test
        @DisplayName("BCrypt 格式应被判定为无需升级")
        void shouldMarkBcryptAsUpToDate() {
            assertThat(passwordUtils.needsUpgrade(passwordUtils.encode("somePassword"))).isFalse();
        }

        @Test
        @DisplayName("空值应判定为无需升级，避免误触发写库")
        void shouldNotUpgradeBlank() {
            assertThat(passwordUtils.needsUpgrade(null)).isFalse();
            assertThat(passwordUtils.needsUpgrade("")).isFalse();
        }
    }

    @Nested
    @DisplayName("完整迁移流程")
    class MigrationFlow {

        @Test
        @DisplayName("老用户用 MD5 登录成功后，密码可升级为 BCrypt 且新旧两种校验均可用")
        void shouldUpgradeLegacyPasswordOnLogin() {
            // 1. 模拟数据库中保存的旧 MD5 密码
            String rawPassword = "userOldPassword";
            String storedInDb = legacyMd5(rawPassword);

            // 2. 迁移前：需要升级，且能用明文登录
            assertThat(passwordUtils.needsUpgrade(storedInDb)).isTrue();
            assertThat(passwordUtils.matches(rawPassword, storedInDb)).isTrue();

            // 3. 执行升级
            String upgraded = passwordUtils.encode(rawPassword);
            assertThat(passwordUtils.needsUpgrade(upgraded)).isFalse();

            // 4. 迁移后：同一明文仍能通过校验，错误明文仍被拒绝
            assertThat(passwordUtils.matches(rawPassword, upgraded)).isTrue();
            assertThat(passwordUtils.matches("wrongPassword", upgraded)).isFalse();
        }
    }
}

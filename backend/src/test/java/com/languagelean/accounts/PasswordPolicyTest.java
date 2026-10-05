package com.languagelean.accounts;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖最小长度、三类字符缺失及空白冒充特殊字符的边界。 */
class PasswordPolicyTest {
    /** BCrypt 按 UTF-8 字节限制，长中文密码不能触发编码器异常。 */
    @org.junit.jupiter.api.Test
    void rejectsPasswordsOverBcryptByteLimit() {
        assertTrue(PasswordPolicy.isValid("a1!" + "b".repeat(69)));
        assertFalse(PasswordPolicy.isValid("a1!" + "b".repeat(70)));
        assertFalse(PasswordPolicy.isValid("a1!" + "字".repeat(24)));
    }
    @ParameterizedTest
    @ValueSource(strings = {"abcdef1!", "ABCDEF1!", "Abcdef12#", "abcde12_"})
    void acceptsEightOrMoreCharactersWithAllThreeCategories(String password) {
        assertTrue(PasswordPolicy.isValid(password));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abcde1!", "abcdefgh!", "abcdef12", "1234567!", "abcdef1 ", "abcdef1\n"})
    void rejectsMissingLengthOrCategory(String password) {
        assertFalse(PasswordPolicy.isValid(password));
    }
}

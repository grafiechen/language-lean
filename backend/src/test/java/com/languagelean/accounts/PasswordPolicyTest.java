package com.languagelean.accounts;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖最小长度、三类字符缺失及空白冒充特殊字符的边界。 */
class PasswordPolicyTest {
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

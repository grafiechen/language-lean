package com.languagelean.accounts;

/** 创建和修改密码共用的规则，防止初始化账号与改密接口的要求不一致。 */
final class PasswordPolicy {
    static final String MESSAGE = "密码至少 8 位，且必须包含字母、数字和特殊字符";

    private PasswordPolicy() {}

    /** 按 Unicode 字符计数；标点和符号算特殊字符，空白及控制字符不算。 */
    static boolean isValid(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 8) return false;
        return password.codePoints().anyMatch(Character::isLetter)
                && password.codePoints().anyMatch(Character::isDigit)
                && java.util.regex.Pattern.compile("[\\p{P}\\p{S}]").matcher(password).find();
    }
}

package com.languagelean.accounts;

/** 账号邮件边界；明文密码和令牌只在发送期间存在，不落库、不写日志。 */
interface AccountMailPort {
    boolean configured();
    void sendInitialPassword(String email, String username, String password);
    void sendPasswordReset(String email, String username, String link);
}

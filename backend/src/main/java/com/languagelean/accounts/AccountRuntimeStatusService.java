package com.languagelean.accounts;

import org.springframework.stereotype.Service;

/** 运行概览仅暴露配置是否齐备；禁止返回邮件地址、主机、密码或重置链接。 */
@Service
public class AccountRuntimeStatusService {
    private final AccountMailPort mail;
    private final AccountMailConfiguration configuration;
    AccountRuntimeStatusService(AccountMailPort mail, AccountMailConfiguration configuration) { this.mail = mail; this.configuration = configuration; }
    public Status status() { return new Status(mail.configured(), mail.configured() && configuration.recoveryConfigured()); }
    public record Status(boolean mailConfigured, boolean passwordRecoveryConfigured) {}
}

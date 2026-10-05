package com.languagelean.accounts;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/** 同步投递账号邮件；投递失败让调用方回滚账号/重置凭据事务。 */
@Service
class SmtpAccountMailService implements AccountMailPort {
    private final AccountMailConfiguration config;
    SmtpAccountMailService(AccountMailConfiguration config) { this.config = config; }
    @Override public boolean configured() {
        return config.isEnabled() && !config.getHost().isBlank() && config.getPort() > 0 && config.getPort() <= 65535
                && AccountMailConfiguration.validEmail(config.getFrom()) && !(config.isSsl() && config.isStarttls());
    }
    /** 初始密码只发送到账号必填邮箱，管理员响应中不会包含它。 */
    @Override public void sendInitialPassword(String email, String username, String password) {
        send(email, "Language Lean · 初始密码", "你好，" + username + "：\n\n账号已创建。\n用户名：" + username
                + "\n初始密码：" + password + "\n\n可使用用户名或邮箱登录，请登录后修改初始密码。\n");
    }
    /** 链接中的随机令牌由重置服务生成，邮件只说明有效期和使用次数。 */
    @Override public void sendPasswordReset(String email, String username, String link) {
        send(email, "Language Lean · 重置密码", "你好，" + username + "：\n\n请打开下面的链接重置密码：\n" + link
                + "\n\n链接有效期为 30 分钟，只能使用一次。若非本人申请，请忽略这封邮件。\n");
    }
    /** 启用 TLS 时必须成功升级；设置短超时，不在日志中输出邮件或 SMTP 凭据。 */
    private void send(String email, String subject, String text) {
        if (!configured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "账号邮件未配置，暂时无法发送");
        var sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost()); sender.setPort(config.getPort()); sender.setDefaultEncoding("UTF-8");
        sender.setUsername(config.getUsername()); sender.setPassword(config.getPassword());
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", String.valueOf(!config.getUsername().isBlank()));
        properties.setProperty("mail.smtp.starttls.enable", String.valueOf(config.isStarttls()));
        properties.setProperty("mail.smtp.starttls.required", String.valueOf(config.isStarttls()));
        properties.setProperty("mail.smtp.ssl.enable", String.valueOf(config.isSsl()));
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000"); properties.setProperty("mail.smtp.writetimeout", "5000");
        var message = new SimpleMailMessage();
        message.setFrom(config.getFrom()); message.setTo(email); message.setSubject(subject); message.setText(text);
        try { sender.send(message); }
        catch (MailException e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, "邮件发送失败，请检查邮件服务后重试"); }
    }
}

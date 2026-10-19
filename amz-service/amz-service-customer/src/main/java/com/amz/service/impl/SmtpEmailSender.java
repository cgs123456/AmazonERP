package com.amz.service.impl;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * SMTP 邮件发送器。
 * <p>
 * <b>为什么是 SMTP 而不是 Amazon Messaging</b>：Messaging API 只有 9 个固定的
 * <b>订单事务性</b>动作（确认定制、确认发货、发票、保修等），<b>没有「自由正文邮件」</b>。
 * 而 {@code amz_email_task} 是通用模板邮件表（含运营手动创建的任务、任意主题与正文），
 * 两者语义对不上。SMTP 是唯一能覆盖该表全部用途的通道。
 * <p>
 * <b>合规边界（必须明确）</b>：Amazon 政策禁止通过邮件邀评/索评，索评只能走站内
 * 合规入口。本发送器不判断内容合规性——那是业务侧的责任——但独立能力
 * {@code CustomerServiceImpl.sendReviewSolicitations} 仍保持「未接入」状态，
 * 不要因为有了 SMTP 就把它改成走邮件。
 * <p>
 * <b>配置缺失即不可用</b>：{@code spring.mail.host} 为空时 {@link #isAvailable()} 返回 false，
 * 调用方据此把任务标 FAILED 并写明原因，<b>绝不伪造 SENT</b>。
 * 这与既有口径一致（见 {@code CustomerEmailServiceImpl.processPendingEmails} 的注释）。
 * <p>
 * <b>为什么不直接注入 {@link JavaMailSender}</b>：{@code spring-boot-starter-mail} 的自动装配
 * 在 {@code spring.mail.host} 为空时仍会创建 sender（指向 localhost:25），
 * 直接注入会让「未配置」看起来像「已配置但发送失败」，排查方向会错。
 * 用 {@link ObjectProvider} + 显式 host 判定，把两种状态分开。
 */
@Slf4j
@Component
public class SmtpEmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** SMTP 主机。为空表示未配置——这是判断「通道是否可用」的唯一依据。 */
    @Value("${spring.mail.host:}")
    private String mailHost;

    /** 发件人地址。SMTP 协议要求 From 必须是合法邮箱。 */
    @Value("${spring.mail.properties.mail.from:}")
    private String fromAddress;

    /** 发件人显示名。 */
    @Value("${spring.mail.properties.mail.from-name:}")
    private String fromName;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    /**
     * 通道是否可用：配置了 host 且相关 Bean 已装配。
     * <p>
     * <b>不探测连通性</b>：每次发送前做一次 SMTP 握手会让批量发送慢一个数量级，
     * 且「连得上」不等于「能发出去」（认证/发件人白名单等问题只在真正发送时暴露）。
     * 真正的失败由 {@link #send} 抛异常并落到任务的 failureReason 上。
     */
    public boolean isAvailable() {
        if (mailHost == null || mailHost.isBlank()) {
            return false;
        }
        return mailSenderProvider.getIfAvailable() != null;
    }

    /**
     * 不可用的原因（用于写入任务的 failureReason，让运营知道该去补什么配置）。
     */
    public String unavailableReason() {
        if (mailHost == null || mailHost.isBlank()) {
            return "SMTP 未配置（spring.mail.host 为空），无法发送邮件";
        }
        return "SMTP 发送器未装配（spring-boot-starter-mail 未生效）";
    }

    /**
     * 发送一封纯文本/HTML 邮件。
     *
     * @param to      收件人
     * @param subject 主题
     * @param body    正文（HTML 片段也按 HTML 发送）
     * @throws IllegalStateException 通道不可用、收件人/发件人缺失，或发送失败
     */
    public void send(String to, String subject, String body) {
        if (!isAvailable()) {
            throw new IllegalStateException(unavailableReason());
        }
        if (to == null || to.isBlank()) {
            // 买家邮箱为空是真实数据缺失（Amazon 只在部分场景返回买家邮箱），
            // 必须点名失败，不能静默跳过——静默会让运营以为已经发了
            throw new IllegalStateException("收件人邮箱为空，无法发送（该订单可能未取得买家邮箱）");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException(
                    "发件人未配置（spring.mail.properties.mail.from 为空），SMTP 要求合法 From 地址");
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException(unavailableReason());
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            String display = (fromName == null || fromName.isBlank()) ? fromAddress : fromName;
            helper.setFrom(fromAddress, display);
            helper.setTo(to);
            helper.setSubject(subject == null ? "" : subject);
            // HTML 由模板正文决定；用 setText(body, true) 支持 HTML，
            // 纯文本正文在 HTML 模式下也能正常显示（不包含标签时浏览器按纯文本渲染）。
            helper.setText(body == null ? "" : body, true);
            sender.send(message);
            log.info("SMTP 邮件已发送 to={} subject={}", maskEmail(to), subject);
        } catch (Exception e) {
            // 不吞：调用方需要拿到原因写入 failureReason。
            // 但异常消息里可能含收件人地址，日志侧脱敏，异常本身保留给业务表。
            log.error("SMTP 邮件发送失败 to={}: {}", maskEmail(to), e.getMessage());
            throw new IllegalStateException("SMTP 发送失败：" + e.getMessage(), e);
        }
    }

    /**
     * 日志脱敏：邮箱只保留首字符与域名。
     * <p>
     * 买家邮箱属于个人信息（CLAUDE.md：敏感信息不得进入日志）。业务表
     * {@code amz_email_task.buyer_email} 保存原文是业务需要，日志不需要。
     */
    static String maskEmail(String email) {
        if (email == null) {
            return "null";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }
}

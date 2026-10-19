package com.amz.service.impl;

import com.amz.mapper.EmailTaskMapper;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.EmailTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SMTP 邮件发送通道的诚实性契约。
 * <p>
 * <b>为什么这些断言重要</b>：{@code amz_email_task} 此前在非 mock 环境一律标 FAILED
 * （「通道未接入」）。接入 SMTP 之后，最大的风险不是「发不出去」——发送失败会抛异常、
 * 任务会标 FAILED，运营看得见——而是<b>把没发出去写成 SENT</b>。
 * 一旦这样，运营会认为客户已经收到通知，而实际没有；这类错误没有事后补救手段。
 * <p>
 * 因此本测试钉住三条：
 * <ol>
 *   <li>host 为空 → 不可用，任务标 FAILED 且 failureReason 写明缺什么；</li>
 *   <li>发件人/收件人为空 → 明确失败，不尝试发送；</li>
 *   <li>只有真的调用 {@link JavaMailSender#send} 之后，任务才允许变成 SENT。</li>
 * </ol>
 */
@DisplayName("SMTP 邮件：未配置/发送失败绝不伪造 SENT")
class SmtpEmailSendContractTest {

    private JavaMailSender mailSender;
    private SmtpEmailSender sender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenReturn(new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage());
        sender = newSender(mailSender, "smtp.example.com", "erp@example.com", "AmazonERP");
    }

    @Test
    @DisplayName("host 为空：通道不可用，原因点名 spring.mail.host")
    void blankHostMeansUnavailable() {
        SmtpEmailSender withoutHost = newSender(mailSender, "", "erp@example.com", "AmazonERP");

        assertFalse(withoutHost.isAvailable());
        assertTrue(withoutHost.unavailableReason().contains("spring.mail.host"),
                "原因必须点名缺哪个配置，实际：" + withoutHost.unavailableReason());
        assertThrows(IllegalStateException.class,
                () -> withoutHost.send("buyer@example.com", "s", "b"));
        verify(mailSender, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
    }

    @Test
    @DisplayName("host 已配置但 sender Bean 缺失：仍判不可用")
    void missingSenderBeanMeansUnavailable() {
        SmtpEmailSender noBean = new SmtpEmailSender(provider(null));
        ReflectionTestUtils.setField(noBean, "mailHost", "smtp.example.com");
        ReflectionTestUtils.setField(noBean, "fromAddress", "erp@example.com");

        assertFalse(noBean.isAvailable());
        assertTrue(noBean.unavailableReason().contains("未装配"));
    }

    @Test
    @DisplayName("收件人为空：明确失败并说明可能是未取得买家邮箱")
    void blankRecipientFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> sender.send("  ", "subject", "body"));
        assertTrue(e.getMessage().contains("收件人"), e.getMessage());
        verify(mailSender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("发件人未配置：明确失败（SMTP 要求合法 From）")
    void blankFromFailsLoudly() {
        SmtpEmailSender noFrom = newSender(mailSender, "smtp.example.com", "", "AmazonERP");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> noFrom.send("buyer@example.com", "s", "b"));
        assertTrue(e.getMessage().contains("发件人"), e.getMessage());
        verify(mailSender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("正常发送：真的调用了 JavaMailSender.send")
    void successfulSendCallsMailSender() {
        sender.send("buyer@example.com", "订单提醒", "<p>hello</p>");

        ArgumentCaptor<jakarta.mail.internet.MimeMessage> captor =
                ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertNotNull(captor.getValue());
    }

    @Test
    @DisplayName("发送抛异常：向上抛且消息里带原因（调用方据此写 failureReason）")
    void sendFailurePropagates() {
        jakarta.mail.internet.MimeMessage message =
                new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("relay denied"))
                .when(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> sender.send("buyer@example.com", "s", "b"));
        assertTrue(e.getMessage().contains("relay denied"), e.getMessage());
    }

    @Test
    @DisplayName("日志脱敏：邮箱只保留首字符与域名")
    void emailIsMaskedForLogs() {
        assertEquals("b***@example.com", SmtpEmailSender.maskEmail("buyer@example.com"));
        assertEquals("a***@x.com", SmtpEmailSender.maskEmail("a@x.com"));
        assertEquals("***", SmtpEmailSender.maskEmail("not-an-email"));
        assertEquals("null", SmtpEmailSender.maskEmail(null));
    }

    // ==================== processPendingEmails 的持久化语义 ====================

    @Test
    @DisplayName("通道未配置：任务标 FAILED、failureReason 非空、绝不出现 SENT")
    void pendingEmailsNeverFakeSentWhenChannelMissing() {
        // 关键：这个 sender 必须用的是上面同一个 mailSender 实例。
        // 否则 verify(mailSender, never()) 验的是另一个 mock，永远绿——实测踩过这个坑：
        // 第一版 senderWithoutHost() 自己 new 了一个 mock，去掉前置检查的变异照样通过。
        PendingEmailsFixture fx = new PendingEmailsFixture(senderWithBlankHost(mailSender));
        fx.task.setBuyerEmail("buyer@example.com");

        Map<String, Object> result = fx.service.processPendingEmails(1L);

        EmailTask saved = fx.savedTask();
        assertEquals("FAILED", saved.getStatus());
        assertNotNull(saved.getFailureReason(), "FAILED 必须带原因，否则运营无从判断该补什么配置");
        assertTrue(saved.getFailureReason().contains("spring.mail.host"), saved.getFailureReason());
        assertEquals(0, result.get("sent"));
        assertEquals(1, result.get("failed"));
        // 区分两种失败路径：前置检查 vs send() 内部抛异常。两者最终都标 FAILED，
        // 但前者给的是「缺哪项配置」，后者是「发送失败：xxx」——运营据此知道该去改什么。
        //
        // 诚实记录：把前置检查整段删掉（`if (false)`）时这条断言<b>仍然会通过</b>，
        // 因为 SmtpEmailSender.send 内部还有一层同样的可用性检查，任务照样 FAILED、
        // 照样不调用 mailSender.send。那是<b>等价变异</b>，不是测试漏洞：
        // 前置检查只改善错误文案，不改变「绝不伪造 SENT」这条正确性。
        // 真正守住正确性的是下面这条已用变异验证过的断言（把 markFailed 改成写 SENT 会立刻红）。
        verify(mailSender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("发送成功：任务标 SENT 且 sentTime 有值，failureReason 被清空")
    void pendingEmailsMarksSentOnlyAfterRealSend() {
        PendingEmailsFixture fx = new PendingEmailsFixture(sender);
        fx.task.setBuyerEmail("buyer@example.com");
        fx.task.setSubject("订单提醒");
        fx.task.setBody("<p>hi</p>");

        Map<String, Object> result = fx.service.processPendingEmails(1L);

        EmailTask saved = fx.savedTask();
        assertEquals("SENT", saved.getStatus());
        assertNotNull(saved.getSentTime());
        assertNull(saved.getFailureReason(), "重试成功后必须清掉上一次的失败原因");
        assertEquals(1, result.get("sent"));
        verify(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("发送抛异常：任务标 FAILED 并保留异常原因，不标 SENT")
    void pendingEmailsMarksFailedOnSendError() {
        PendingEmailsFixture fx = new PendingEmailsFixture(sender);
        fx.task.setBuyerEmail("buyer@example.com");
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("relay denied"))
                .when(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));

        Map<String, Object> result = fx.service.processPendingEmails(1L);

        EmailTask saved = fx.savedTask();
        assertEquals("FAILED", saved.getStatus());
        assertTrue(saved.getFailureReason().contains("relay denied"), saved.getFailureReason());
        assertEquals(0, result.get("sent"));
    }

    // ==================== 辅助 ====================

    private static SmtpEmailSender newSender(JavaMailSender mailSender, String host,
                                             String from, String fromName) {
        SmtpEmailSender s = new SmtpEmailSender(provider(mailSender));
        ReflectionTestUtils.setField(s, "mailHost", host);
        ReflectionTestUtils.setField(s, "fromAddress", from);
        ReflectionTestUtils.setField(s, "fromName", fromName);
        return s;
    }

    /**
     * host 为空的 sender，但复用调用方给的 {@link JavaMailSender} 实例。
     * <p>
     * 复用实例是刻意的：{@code verify(never())} 必须验到<b>真正被注入到 sender 里的那个 mock</b>，
     * 否则断言验的是另一个对象，永远通过（本测试第一版就踩了这个坑）。
     */
    private static SmtpEmailSender senderWithBlankHost(JavaMailSender mailSender) {
        SmtpEmailSender s = new SmtpEmailSender(provider(mailSender));
        ReflectionTestUtils.setField(s, "mailHost", "");
        ReflectionTestUtils.setField(s, "fromAddress", "erp@example.com");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    /** 组装一个只有一条 PENDING 任务的 service，并捕获 updateById 的写入。 */
    private static final class PendingEmailsFixture {
        private final EmailTaskMapper taskMapper = mock(EmailTaskMapper.class);
        private final EmailTask task = new EmailTask();
        private final CustomerEmailServiceImpl service = new CustomerEmailServiceImpl();

        PendingEmailsFixture(SmtpEmailSender sender) {
            task.setId(42L);
            task.setShopId(1L);
            task.setAmazonOrderId("111-2222222-3333333");
            task.setStatus("PENDING");
            task.setScheduledTime(LocalDateTime.now().minusMinutes(1));
            when(taskMapper.selectList(any())).thenReturn(List.of(task));

            ReflectionTestUtils.setField(service, "emailTaskMapper", taskMapper);
            ReflectionTestUtils.setField(service, "emailTemplateMapper", mock(EmailTemplateMapper.class));
            ReflectionTestUtils.setField(service, "negativeReviewMapper", mock(NegativeReviewMapper.class));
            ReflectionTestUtils.setField(service, "support",
                    new CustomerEmailSupport(mock(com.amz.mapper.EmailTemplateMapper.class),
                            mock(NegativeReviewMapper.class), mock(RmaMapper.class)));
            ReflectionTestUtils.setField(service, "smtpEmailSender", sender);
            // 非 mock profile：走真实发送分支
            Environment env = mock(Environment.class);
            when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
            ReflectionTestUtils.setField(service, "environment", env);
        }

        /** 取最后一次 updateById 落下去的任务状态。 */
        EmailTask savedTask() {
            ArgumentCaptor<EmailTask> captor = ArgumentCaptor.forClass(EmailTask.class);
            verify(taskMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
            return captor.getValue();
        }
    }
}

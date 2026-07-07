package com.amz.mq.consumer;

import com.amz.constant.MqConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消息通知消费者。
 * <p>
 * 已清理社交场景的关注/点赞/收藏通知，
 * 保留登录通知。ERP 业务通知（库存预警/订单异常等）
 * 可通过扩展 MessageTypeEnum 新增队列实现。
 */
@Component
@Slf4j
public class MessageNoticeConsumer {

    @RabbitListener(queues = {MqConstant.LOGIN_NOTICE_QUEUE})
    public void loginNotice(Integer userId) {
        log.info("登录用户的ID：{}", userId);
    }
}

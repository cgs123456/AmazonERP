package com.amz.mq.consumer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.amz.constant.MqConstant;
import com.amz.model.pojo.Attention;
import com.amz.model.pojo.Collection;
import com.amz.model.pojo.LikeVo;
import com.amz.model.pojo.Message;
import com.amz.session.Session;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;

@Component
@Slf4j
public class MessageNoticeConsumer {

    private final Gson gson = new Gson();

    @RabbitListener(queues = {MqConstant.LOGIN_NOTICE_QUEUE})
    public void loginNotice(Integer userId) {
        log.info("登录用户的ID：{}", userId);
    }

    @RabbitListener(queues = MqConstant.ATTENTION_NOTICE_QUEUE)
    public void attentionNotice(Message<Attention> message) {
        Attention attention = message.getObj();
        Channel channel = Session.getChannel(message.getNoticeId());
        if (channel != null) {
            log.info("关注用户的ID：{}, 关注的人ID：{}", attention.getOwnId(), attention.getOtherId());
            String json = gson.toJson(message);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    @RabbitListener(queues = MqConstant.LIKE_NOTICE_QUEUE)
    public void likeNotice(String messageJson) {
        try {
            log.info("收到点赞消息JSON: {}", messageJson);

            // 使用 TypeToken 正确解析泛型类型
            Type messageType = new TypeToken<Message<LikeVo>>(){}.getType();
            Message<LikeVo> message = gson.fromJson(messageJson, messageType);
            Integer noticeId = message.getNoticeId();
            LikeVo likeVo = message.getObj();

            // 获取接收通知用户的 WebSocket 连接
            Channel channel = Session.getChannel(noticeId);
            if (channel != null) {
                log.info("点赞用户的ID：{}, 点赞的笔记ID：{}", likeVo.getLike().getUserId(), likeVo.getLike().getNoteId());
                channel.writeAndFlush(new TextWebSocketFrame(messageJson));
            } else {
                log.warn("用户 {} 的WebSocket连接不存在", noticeId);
            }
        } catch (Exception e) {
            log.error("处理点赞消息失败: {}", messageJson, e);
        }
    }

    @RabbitListener(queues = MqConstant.COLLECTION_NOTICE_QUEUE)
    public void collectionNotice(Message<Collection> message) {
        Collection collection = message.getObj();
        Channel channel = Session.getChannel(message.getNoticeId());
        if (channel != null) {
            log.info("收藏用户的ID：{}, 收藏的笔记ID：{}", collection.getUserId(), collection.getNoteId());
            String json = gson.toJson(message);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }
}

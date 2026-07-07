package com.amz.handler;

import com.auth0.jwt.exceptions.TokenExpiredException;
import com.amz.session.Session;
import com.amz.util.JwtUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@ChannelHandler.Sharable
@Slf4j
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    @Override
    public void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        String message = msg.text();
        
        // 处理心跳消息
        if ("ping".equals(message)) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame("pong"));
            log.debug("收到心跳ping，返回pong");
            return;
        }
        
        // 处理token认证
        if (!StringUtils.isBlank(message)) {
            try {
                Integer userId = Integer.valueOf(JwtUtil.parseToken(message));
                Session.bind(userId, ctx.channel());
                ctx.channel().writeAndFlush(new TextWebSocketFrame("auth_success"));
                log.info("用户登录，登录用户的ID为：{}", userId);
            } catch (TokenExpiredException e) {
                log.error("用户token过期，请重新认证后重试");
                ctx.channel().writeAndFlush(new TextWebSocketFrame("auth_failed:token_expired"));
            } catch (Exception e) {
                log.error("用户认证失败：{}", e.getMessage());
                ctx.channel().writeAndFlush(new TextWebSocketFrame("auth_failed:invalid_token"));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx){
        Channel channel = ctx.channel();
        Integer userId = Session.getUserId(channel);
        if(channel != null && userId != null) {
            Session.unbind(userId, ctx.channel());
            log.info("用户断开连接，用户ID：{}", userId);
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket异常：{}", cause.getMessage());
        ctx.close();
    }
}

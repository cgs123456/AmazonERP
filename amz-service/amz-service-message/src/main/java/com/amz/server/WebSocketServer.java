package com.amz.server;

import com.amz.handler.WebSocketChannelHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * netty服务器
 */
@Component
@Slf4j
public class WebSocketServer {

    private final WebSocketChannelHandler handler = new WebSocketChannelHandler();

    EventLoopGroup boosGroup = new NioEventLoopGroup(1);
    EventLoopGroup workGroup = new NioEventLoopGroup(10);

    @PostConstruct
    public void run(){
        log.info("启动Netty服务器");
        new Thread(() -> {
            try{
                ServerBootstrap bootstrap = new ServerBootstrap();
                ChannelFuture future = bootstrap.group(boosGroup, workGroup)
                        .option(ChannelOption.SO_BACKLOG, 1024)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(handler)
                        .bind(8888)
                        .sync();
                log.info("Netty WebSocket服务器启动成功，端口：8888");
                future.channel().closeFuture().sync();
            } catch (Exception e) {
                log.error("Netty服务器启动失败：{}", e.getMessage());
            } finally {
                boosGroup.shutdownGracefully();
                workGroup.shutdownGracefully();
            }
        }, "netty-websocket-server").start();
    }
}

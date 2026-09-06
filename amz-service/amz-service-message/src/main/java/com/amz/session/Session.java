package com.amz.session;

import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;

public class Session {

    private static final ConcurrentHashMap<Integer, Channel> userIdChannelMap = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Channel, Integer> channelUserIdMap = new ConcurrentHashMap<>();

    public static void bind(Integer userId, Channel channel) {
        Channel old = userIdChannelMap.put(userId, channel);
        // 同用户换连接时清理旧连接的反向映射，避免 channelUserIdMap 堆积幽灵条目；
        // 注意：此处不主动关闭旧连接（多端登录是否互踢属产品策略，当前保持不断连）。
        if (old != null && old != channel) {
            channelUserIdMap.remove(old);
        }
        channelUserIdMap.put(channel, userId);
    }

    public static void unbind(Integer userId, Channel channel) {
        // 条件删除：仅当映射仍指向该 channel 时才移除，避免旧连接的关闭事件
        // 把同用户新登录的连接误删下线。
        userIdChannelMap.remove(userId, channel);
        channelUserIdMap.remove(channel, userId);
    }

    public static Integer getUserId(Channel channel) {
        return channelUserIdMap.get(channel);
    }

    public static Channel getChannel(Integer userId) {
        return userIdChannelMap.get(userId);
    }
}

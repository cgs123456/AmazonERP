package com.amz.session;

import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;

public class Session {

    private static final ConcurrentHashMap<Integer, Channel> userIdChannelMap = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Channel, Integer> channelUserIdMap = new ConcurrentHashMap<>();

    public static void bind(Integer userId, Channel channel) {
        userIdChannelMap.put(userId, channel);
        channelUserIdMap.put(channel, userId);
    }

    public static void unbind(Integer userId, Channel channel) {
        userIdChannelMap.remove(userId);
        channelUserIdMap.remove(channel);
    }

    public static Integer getUserId(Channel channel) {
        return channelUserIdMap.get(channel);
    }

    public static Channel getChannel(Integer userId) {
        return userIdChannelMap.get(userId);
    }
}

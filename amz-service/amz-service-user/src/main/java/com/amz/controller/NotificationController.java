package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.model.pojo.Notification;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知历史接口（P1.6 消息中心落库）。
 * <p>
 * <b>写入</b>：{@code POST /user/notification} 供 message 服务在推送时落库
 * （白名单只放行 amz-service-message）。落库与推送解耦：message 推送失败
 * 不影响落库，用户不在线也照落——历史兜住的正是「错过」的场景。
 * <p>
 * <b>读取</b>：{@code GET /user/notification/list} 按当前登录用户返回最近 100 条
 * （通知是尾随型数据：只关心最近的，翻页意义小；硬上限防无界读）。
 */
@Slf4j
@RestController
@RequestMapping("/user/notification")
public class NotificationController {

    /** 历史读取硬上限：通知是尾随型数据，超过部分让用户按类型/时间收窄后再看。 */
    static final int HISTORY_LIMIT = 100;

    @Autowired
    private com.amz.mapper.NotificationMapper notificationMapper;

    /**
     * 落库一条通知（message 服务专用）。
     */
    @InternalServiceAccess("amz-service-message")
    @PostMapping
    public Result<Boolean> save(@RequestBody Notification notification) {
        if (notification == null || notification.getUserId() == null) {
            return Result.failure("userId 不能为空");
        }
        if (notification.getContent() != null && notification.getContent().length() > 1000) {
            // 与列宽对齐，超长截断而不是让 DB 报错把推送链路打成失败
            notification.setContent(notification.getContent().substring(0, 1000));
        }
        notification.setId(null);
        notification.setIsRead(0);
        try {
            notificationMapper.insert(notification);
            return Result.success(true);
        } catch (Exception e) {
            // 落库失败不能让 message 推送链路挂掉：推送是主动作，历史是旁路
            log.warn("通知落库失败 userId={}: {}", notification.getUserId(), e.getMessage());
            return Result.failure("通知落库失败：" + e.getMessage());
        }
    }

    /**
     * 当前用户的通知历史（最近 100 条，含已读）。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/list")
    public Result<List<Notification>> list() {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("未获取到登录用户");
        }
        LambdaQueryWrapper<Notification> q = new LambdaQueryWrapper<>();
        q.eq(Notification::getUserId, userId.longValue())
                .orderByDesc(Notification::getId)
                .last("LIMIT " + HISTORY_LIMIT);
        return Result.success(notificationMapper.selectList(q));
    }

    /**
     * 标记已读（单条）。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @PostMapping("/{id}/read")
    public Result<Boolean> markRead(@PathVariable Long id) {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("未获取到登录用户");
        }
        // 带 userId 条件：防止改到别人的通知（越权写）
        int affected = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId.longValue())
                .set(Notification::getIsRead, 1));
        if (affected == 0) {
            return Result.failure("通知不存在或无权访问");
        }
        return Result.success(true);
    }

    /**
     * 全部标记已读。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @PostMapping("/read-all")
    public Result<Boolean> markAllRead() {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("未获取到登录用户");
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId.longValue())
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
        return Result.success(true);
    }

    /**
     * 未读数（顶栏红点用）。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("未获取到登录用户");
        }
        return Result.success(notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId.longValue())
                .eq(Notification::getIsRead, 0)));
    }
}

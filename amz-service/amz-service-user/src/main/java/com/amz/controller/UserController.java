package com.amz.controller;

import com.amz.model.dto.UserEditDto;
import com.amz.model.vo.UserVo;
import com.amz.result.Result;
import com.amz.service.UserService;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.model.pojo.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.ParseException;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/getInfo")
    public Result<UserVo> getInfo() throws ParseException {
        return userService.getInfo();
    }

    /**
     * 用户列表（ADMIN）：账号管理用。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "用户", action = "查询", description = "用户管理列表")
    @GetMapping("/list")
    public Result<java.util.List<com.amz.model.pojo.User>> listUsers() {
        try {
            return Result.success(userService.listUsers());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 修改用户角色（ADMIN）。禁止修改自己的角色（防 ADMIN 流失）。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "用户", action = "改角色", description = "修改用户角色")
    @PostMapping("/{userId}/role")
    public Result<com.amz.model.pojo.User> updateRole(@PathVariable Integer userId,
                                                      @RequestParam String role) {
        try {
            return Result.success(userService.updateRole(userId, role));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 启用/禁用账号（ADMIN）。禁用立即拦登录；已签发 token 过期前仍有效（同 shops claim 限制）。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "用户", action = "启停", description = "启用或禁用账号")
    @PostMapping("/{userId}/status")
    public Result<com.amz.model.pojo.User> updateStatus(@PathVariable Integer userId,
                                                        @RequestParam Integer status) {
        try {
            return Result.success(userService.updateStatus(userId, status));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.failure(e.getMessage());
        }
    }

    @GetMapping("/getUserById/{userId}")
    public Result<User> getUserById(@PathVariable Integer userId) {
        return userService.getUserById(userId);
    }

    @PostMapping("/updateImage")
    public Result<Void> updateImage(@RequestParam("image") MultipartFile file) throws IOException {
        // 文件校验
        if (file == null || file.isEmpty()) {
            return Result.failure("文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.failure("只能上传图片文件");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            return Result.failure("文件大小不能超过10MB");
        }
        return userService.updateImage(file);
    }

    @PutMapping("/editInfo")
    public Result<Void> editInfo(@RequestBody UserEditDto userEditDto) {
        return userService.editInfo(userEditDto);
    }
}

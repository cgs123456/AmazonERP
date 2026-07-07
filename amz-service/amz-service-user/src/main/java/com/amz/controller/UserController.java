package com.amz.controller;

import com.amz.model.dto.UserEditDto;
import com.amz.model.vo.UserVo;
import com.amz.result.Result;
import com.amz.service.UserService;
import com.amz.model.pojo.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
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

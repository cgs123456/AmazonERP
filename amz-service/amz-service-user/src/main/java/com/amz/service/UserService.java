package com.amz.service;

import com.amz.model.dto.UserEditDto;
import com.amz.model.vo.UserVo;
import com.amz.result.Result;
import com.amz.model.pojo.User;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.ParseException;

public interface UserService {
    Result<UserVo> getInfo() throws ParseException;

    Result<User> getUserById(Integer userId);

    Result<Void> updateImage(MultipartFile file) throws IOException;

    Result<Void> editInfo(UserEditDto userEditDto);
    /**
     * 用户列表（ADMIN）：账号管理用，返回 id/手机号/昵称/角色/状态/创建时间。
     */
    java.util.List<com.amz.model.pojo.User> listUsers();

    /**
     * 修改用户角色（ADMIN）。禁止修改自己的角色（防最后一个 ADMIN 自降后无人管理）。
     */
    com.amz.model.pojo.User updateRole(Integer userId, String role);

    /**
     * 启用/禁用账号（ADMIN）。禁止禁用自己。禁用立即拦登录；
     * 已签发 token 在过期前仍有效（鉴权路径不查库的既有取舍，同 shops claim 限制）。
     */
    com.amz.model.pojo.User updateStatus(Integer userId, Integer status);
}

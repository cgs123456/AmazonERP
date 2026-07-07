package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.amz.constant.ExceptionConstant;
import com.amz.context.UserContext;
import com.amz.exception.FileIsNullException;
import com.amz.exception.UserNoExistException;
import com.amz.mapper.UserMapper;
import com.amz.model.dto.UserEditDto;
import com.amz.model.pojo.User;
import com.amz.model.vo.UserVo;
import com.amz.result.Result;
import com.amz.service.UserService;
import com.amz.util.OssUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private OssUtil ossUtil;

    @Override
    public Result<UserVo> getInfo() {
        // 1.获取登录用户信息
        Integer userId = UserContext.getUserId();
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getId, userId);
        User user = userMapper.selectOne(queryWrapper);
        if (user == null) {
            throw new UserNoExistException(ExceptionConstant.USER_NO_EXIST);
        }
        // 2.设置userVo
        UserVo userVo = new UserVo();
        userVo.setUser(user);
        // 3.根据生日生成年龄
        if (!StringUtils.isBlank(user.getBirthday())) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                java.util.Date birthDate = sdf.parse(user.getBirthday());
                java.util.Calendar now = java.util.Calendar.getInstance();
                java.util.Calendar birthCal = java.util.Calendar.getInstance();
                birthCal.setTime(birthDate);
                int age = now.get(java.util.Calendar.YEAR) - birthCal.get(java.util.Calendar.YEAR);
                if (now.get(java.util.Calendar.DAY_OF_YEAR) < birthCal.get(java.util.Calendar.DAY_OF_YEAR)) {
                    age--;
                }
                userVo.setAge(age);
            } catch (Exception e) {
                log.error("解析生日日期失败: {}", user.getBirthday(), e);
                userVo.setAge(0);
            }
        }
        // 4.返回结果
        return Result.success(userVo);
    }

    @Override
    public Result<User> getUserById(Integer userId) {
        log.info("根据id查询用户信息...");
        return Result.success(userMapper.selectById(userId));
    }

    @Override
    public Result<Void> updateImage(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new FileIsNullException(ExceptionConstant.FILE_IS_NULL);
        }
        log.info("用户更新头像...");
        // 1.上传头像
        String url = ossUtil.uploadImg(file.getBytes());
        // 2.根据userId更新数据库
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getId, UserContext.getUserId());
        User user = userMapper.selectOne(queryWrapper);
        if (user == null) {
            throw new UserNoExistException(ExceptionConstant.USER_NO_EXIST);
        }
        // 3.设置头像
        user.setImage(url);
        // 4.更新数据库
        userMapper.updateById(user);
        return Result.success(null);
    }

    @Override
    public Result<Void> editInfo(UserEditDto dto) {
        log.info("用户更新个人信息...");
        Integer userId = UserContext.getUserId();
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, userId);
        if (dto.getNickname() != null) updateWrapper.set(User::getNickname, dto.getNickname());
        if (dto.getImage() != null) updateWrapper.set(User::getImage, dto.getImage());
        if (dto.getSex() != null) updateWrapper.set(User::getSex, dto.getSex());
        if (dto.getBirthday() != null) updateWrapper.set(User::getBirthday, dto.getBirthday());
        if (dto.getAddress() != null) updateWrapper.set(User::getAddress, dto.getAddress());
        if (dto.getSchool() != null) updateWrapper.set(User::getSchool, dto.getSchool());
        if (dto.getIdentity() != null) updateWrapper.set(User::getIdentity, dto.getIdentity());
        userMapper.update(null, updateWrapper);
        return Result.success(null);
    }
}

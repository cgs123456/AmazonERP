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
            } catch (java.text.ParseException e) {
                log.error("解析生日日期格式失败: {}", user.getBirthday(), e);
                userVo.setAge(0);
            }
        }
        // 4.返回结果
        return Result.success(userVo);
    }

    @Override
    public Result<User> getUserById(Integer userId) {
        log.info("根据id查询用户信息...");
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 用户不存在时明确失败：禁止 success(null) 把 NPE 抛给调用方
            return Result.failure("用户不存在");
        }
        return Result.success(user);
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

    /** 列宽与 amz_user 的 DDL 对齐：V1__init.sql:12-18（nickname 50 / image 500 / address 200 / birthday 20，sex 是 TINYINT）。 */
    static final int NICKNAME_MAX_LENGTH = 50;
    static final int IMAGE_MAX_LENGTH = 500;
    static final int ADDRESS_MAX_LENGTH = 200;
    static final int BIRTHDAY_MAX_LENGTH = 20;

    private static String overLengthError(String label, String value, int max) {
        if (value == null || value.length() <= max) {
            return null;
        }
        return label + "最长 " + max + " 字符，当前 " + value.length();
    }

    @Override
    public Result<Void> editInfo(UserEditDto dto) {
        log.info("用户更新个人信息...");
        if (dto == null) {
            return Result.failure("没有可更新的字段");
        }

        // 校验必须在拼 SQL 之前：MySQL 跑默认 STRICT_TRANS_TABLES，
        // 超长报 1406、把 "男" 塞进 TINYINT 报 1366，两者最后都只剩一句「服务器内部错误」，
        // 用户看不出是哪个字段错。列宽取自 DDL，改动时两边要一起改。
        String over = overLengthError("昵称", dto.getNickname(), NICKNAME_MAX_LENGTH);
        if (over == null) over = overLengthError("头像地址", dto.getImage(), IMAGE_MAX_LENGTH);
        if (over == null) over = overLengthError("地址", dto.getAddress(), ADDRESS_MAX_LENGTH);
        if (over == null) over = overLengthError("生日", dto.getBirthday(), BIRTHDAY_MAX_LENGTH);
        if (over != null) {
            return Result.failure(over);
        }

        String birthday = dto.getBirthday();
        if (birthday != null && !birthday.isEmpty()) {
            try {
                java.time.LocalDate.parse(birthday);
            } catch (java.time.format.DateTimeParseException e) {
                return Result.failure("生日格式必须是 yyyy-MM-dd，实际收到「" + birthday + "」");
            }
        }

        // sex 在实体里是 String、在库里是 TINYINT：只接受能落进该列的整数，
        // 空串也无法表示（库里没有"未知"以外的空值语义），一律拒绝而不是让驱动报错。
        String sex = dto.getSex();
        if (sex != null) {
            int sexValue;
            try {
                sexValue = Integer.parseInt(sex.trim());
            } catch (NumberFormatException e) {
                return Result.failure("性别必须是 TINYINT 范围内的数字（当前接口按字符串传输），实际收到「" + sex + "」");
            }
            if (sexValue < Byte.MIN_VALUE || sexValue > Byte.MAX_VALUE) {
                return Result.failure("性别必须在 -128..127（列为 TINYINT），实际 " + sexValue);
            }
        }

        boolean hasUpdate = dto.getNickname() != null || dto.getImage() != null
                || sex != null || birthday != null || dto.getAddress() != null;
        if (!hasUpdate) {
            // 过去这里会拼出一条没有任何 SET 的 UPDATE 直接报错：DTO 里的
            // signature/school/identity 三字段在表里没有列，只带它们等于什么都没改。
            log.warn("个人资料更新被拒绝：请求里没有可落库的字段（signature/school/identity 在表里已无列）");
            return Result.failure("没有可更新的字段（个性签名、学校、证件号已不是可存储字段）");
        }
        if (dto.getSignature() != null || dto.getSchool() != null || dto.getIdentity() != null) {
            log.warn("请求携带了表里没有列的字段（signature/school/identity），这些值不会保存");
        }

        Integer userId = UserContext.getUserId();
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, userId);
        if (dto.getNickname() != null) updateWrapper.set(User::getNickname, dto.getNickname());
        if (dto.getImage() != null) updateWrapper.set(User::getImage, dto.getImage());
        if (sex != null) updateWrapper.set(User::getSex, sex.trim());
        if (dto.getBirthday() != null) updateWrapper.set(User::getBirthday, dto.getBirthday());
        if (dto.getAddress() != null) updateWrapper.set(User::getAddress, dto.getAddress());
        // school/identity 字段已从 User 实体删除, 不再更新
        userMapper.update(null, updateWrapper);
        return Result.success(null);
    }
}

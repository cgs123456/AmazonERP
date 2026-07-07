package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.constant.ExceptionConstant;
import com.amz.constant.MqConstant;
import com.amz.constant.RedisConstant;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.UserMapper;
import com.amz.result.Result;
import com.amz.service.LoginService;
import com.amz.model.dto.LoginDto;
import com.amz.model.pojo.User;
import com.amz.util.CodeUtil;
import com.amz.util.JwtUtil;
import com.amz.util.NumberUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class LoginServiceImpl implements LoginService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public Result<String> send(String phone) {
        // 手机号格式校验
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            return Result.failure("手机号格式不正确");
        }
        // 1.生成验证码
        String code = CodeUtil.generateCode(4);
        // 2.保存到redis
        redisTemplate.opsForValue().set(
                RedisConstant.PHONE_CODE.concat(phone), code, 60, TimeUnit.SECONDS);
        // 3.返回结果（验证码不再回显给前端）
        return Result.success("验证码已发送");
    }

    @Override
    public Result<String> verify(LoginDto loginDto) {
        // 1.从redis获取验证码
        String cacheCode = redisTemplate.opsForValue().get(
                RedisConstant.PHONE_CODE.concat(loginDto.getPhone()));
        // 2.校验验证码
        if (cacheCode == null || !cacheCode.equals(loginDto.getCode())) {
            throw new CodeErrorException(ExceptionConstant.CODE_ERROR);
        }
        // 3.删除redis中的验证码
        redisTemplate.delete(RedisConstant.PHONE_CODE.concat(loginDto.getPhone()));
        // 4.获取用户id
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, loginDto.getPhone());
        User dbUser = userMapper.selectOne(queryWrapper);
        Integer userId;
        // 5.判断用户是否存在
        if (dbUser == null) {
            // 5.1 注册
            User user = new User();
            user.setPhone(loginDto.getPhone());
            user.setNickname("普通用户");
            user.setImage("https://i.pravatar.cc/150?img=0");
            // 5.2 生成用户号
            Long number = NumberUtil.getNumber();
            user.setNumber(number);
            userMapper.insert(user);
            userId = user.getId();
        } else {
            userId = dbUser.getId();
        }
        // 6.生成token
        String token = JwtUtil.createToken(userId);

        // 向mq中发送消息
        rabbitTemplate.convertAndSend(MqConstant.MESSAGE_NOTICE_EXCHANGE, MqConstant.LOGIN_KEY, userId);

        // 7.返回token
        return Result.success(token);
    }
}

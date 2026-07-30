package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.constant.ExceptionConstant;
import com.amz.constant.MqConstant;
import com.amz.constant.RedisConstant;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.UserMapper;
import com.amz.mapper.UserShopMapper;
import com.amz.model.pojo.UserShop;
import com.amz.result.Result;
import com.amz.service.LoginService;
import com.amz.model.dto.LoginDto;
import com.amz.model.pojo.User;
import com.amz.util.CodeUtil;
import com.amz.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LoginServiceImpl implements LoginService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserShopMapper userShopMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${user.default-avatar-url:https://i.pravatar.cc/150?img=0}")
    private String defaultAvatarUrl;

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
        String role;
        // 5.判断用户是否存在
        if (dbUser == null) {
            // 5.1 注册（新用户默认 VIEWER，由 DB 列默认值保证）
            User user = new User();
            user.setPhone(loginDto.getPhone());
            user.setNickname("普通用户");
            user.setImage(defaultAvatarUrl);
            userMapper.insert(user);
            userId = user.getId();
            role = user.getRole() == null ? "VIEWER" : user.getRole();
        } else {
            userId = dbUser.getId();
            role = dbUser.getRole() == null ? "VIEWER" : dbUser.getRole();
        }
        // 6.查询用户授权的店铺列表
        LambdaQueryWrapper<UserShop> shopQuery = new LambdaQueryWrapper<>();
        shopQuery.eq(UserShop::getUserId, userId.longValue());
        List<UserShop> userShops = userShopMapper.selectList(shopQuery);
        List<Long> shopIds = userShops.stream()
                .map(UserShop::getShopId)
                .collect(Collectors.toList());

        // 7.生成token（携带 shops + role claim）
        String token = jwtUtil.createToken(userId, shopIds, role);

        // 向mq中发送消息
        rabbitTemplate.convertAndSend(MqConstant.MESSAGE_NOTICE_EXCHANGE, MqConstant.LOGIN_KEY, userId);

        // 8.返回token
        return Result.success(token);
    }
}

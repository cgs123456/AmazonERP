package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.constant.MqConstant;
import com.amz.constant.RedisConstant;
import com.amz.enums.AttentionTypeEnum;
import com.amz.enums.MessageTypeEnum;
import com.amz.mapper.AttentionMapper;
import com.amz.model.pojo.Message;
import com.amz.model.pojo.Attention;
import com.amz.result.Result;
import com.amz.service.AttentionService;
import com.amz.context.UserContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AttentionServiceImpl implements AttentionService {

    @Autowired
    private AttentionMapper attentionMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public Result<Integer> isAttention(Integer otherId) {
        int flag;
        // 1.获取登录用户id
        Integer ownId = UserContext.getUserId();
        // 2.判断是否是自己
        if (otherId.intValue() == ownId.intValue()) {
            flag = AttentionTypeEnum.OWN.getCode();
        } else if(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisConstant.ATTENTION_CACHE + ownId, otherId))) {
            flag = AttentionTypeEnum.ATTENTION.getCode();
        } else {
            flag = AttentionTypeEnum.INATTENTION.getCode();
        }
        return Result.success(flag);
    }

    @Override
    public Result<Void> attention(Integer otherId) {
        // 1.获取登录用户id
        Integer ownId = UserContext.getUserId();
        // 2.设置pojo
        Attention attention = new Attention();
        attention.setOtherId(otherId);
        attention.setOwnId(ownId);
        // 3.判断是否关注过
        //Boolean isAttention = redisTemplate.opsForSet().isMember(ATTENTION_SET_CACHE + ownId, otherId);
        Boolean isAttention = redisTemplate.opsForValue().getBit(
                RedisConstant.ATTENTION_CACHE + ownId, otherId);

        // 4.关注或取关
        if(Boolean.TRUE.equals(isAttention)){
            // 4.1 取关
            attentionMapper.deleteById(attention);
            redisTemplate.opsForValue().setBit(RedisConstant.ATTENTION_CACHE + ownId, otherId, false);
        }else{
            // 4.2 关注
            attentionMapper.insert(attention);
            redisTemplate.opsForValue().setBit(RedisConstant.ATTENTION_CACHE + ownId, otherId, true);

            // 用户关注，通知消息
            Message attentionMessage = new Message();
            attentionMessage.setType(MessageTypeEnum.ATTENTION.getCode());
            attentionMessage.setNoticeId(otherId);
            attentionMessage.setObj(attention);
            rabbitTemplate.convertAndSend(MqConstant.MESSAGE_NOTICE_EXCHANGE, MqConstant.ATTENTION_KEY, attentionMessage);
        }
        return Result.success(null);
    }

    @Override
    public Result<List<Attention>> getAttention(Integer userId) {
        LambdaQueryWrapper<Attention> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Attention::getOwnId, userId);
        List<Attention> attentionList = attentionMapper.selectList(queryWrapper);
        return Result.success(attentionList);
    }
}

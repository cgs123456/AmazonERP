package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.CouponMapper;
import com.amz.mapper.UserCouponMapper;
import com.amz.model.pojo.Coupon;
import com.amz.model.vo.CouponVo;
import com.amz.result.Result;
import com.amz.service.CouponService;
import com.amz.util.IsExpireUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CouponServiceImpl implements CouponService {

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private CouponMapper couponMapper;

    @Override
    public Result<List<CouponVo>> getCouponsByUserId() {
        List<CouponVo> couponsByUserId = getCouponsByUserId(UserContext.getUserId());
        return Result.success(couponsByUserId);
    }

    public List<CouponVo> getCouponsByUserId(Integer userId) {
        // 根据用户 id 查找到用户可用的优惠券
        List<Coupon> couponsByUserId = userCouponMapper.getCouponsByUserId(userId);

        return couponsByUserId.stream().map(coupon -> {
            CouponVo couponVo = new CouponVo();
            couponVo.setCoupon(coupon);

            // 判断是否可用（是否过期，是否有库存）
            // 修复：isExpire返回true表示已过期，需要取反
            boolean isUsable = coupon.getStock() > 0 && !IsExpireUtil.isExpire(coupon.getStartTime(), coupon.getEndTime());
            couponVo.setIsUsable(isUsable);
            return couponVo;
        }).collect(Collectors.toList());
    }

    @Override
    public Result<Void> useCoupon(Long couponId) {
        useCoupon(couponId, UserContext.getUserId());
        return Result.success(null);
    }

    public void useCoupon(Long couponId, Integer userId) {
        RLock lock = redissonClient.getLock("useCoupon:" + couponId);
        boolean res = false;
        try {
            res = lock.tryLock(100, TimeUnit.SECONDS);
            if (res) {
                // 获取数据库优惠券的库存
                Coupon coupon = couponMapper.selectById(couponId);
                Integer stock = coupon.getStock();
                if (stock > 0) {
                    log.info("扣减库存...");
                    coupon.setStock(stock - 1);
                    couponMapper.updateById(coupon);
                } else {
                    // 库存不足时返回失败结果
                    throw new RuntimeException("优惠券库存不足");
                }
            } else {
                // 获取锁失败时返回失败结果
                throw new RuntimeException("获取优惠券锁失败，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁时发生异常", e);
            throw new RuntimeException("获取优惠券锁时线程被中断", e);
        } finally {
            // 只有在成功获取锁时才释放锁
            if (res) {
                lock.unlock();
            }
        }
    }

    @Override
    public Result<Void> insertCoupon(Coupon coupon) {
        couponMapper.insert(coupon);
        return Result.success(null);
    }
}

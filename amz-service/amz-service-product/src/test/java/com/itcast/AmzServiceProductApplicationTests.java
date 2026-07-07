package com.amz;

import com.amz.model.pojo.Coupon;
import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.ProductAttribute;
import com.amz.model.vo.ProductVo;
import com.amz.result.Result;
import com.amz.service.CouponService;
import com.amz.service.ProductService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class AmzServiceProductApplicationTests {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private CouponService couponService;

    @Test
    void contextLoads() {
    }

    private static int stock = 50;

    @Test
    void testRedission() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(100);
        for (int i = 0; i < 100; i++) {
            new Thread(() -> {
                RLock lock = redissonClient.getLock("itcast");
                try {
                    boolean res = lock.tryLock(100, 10, TimeUnit.SECONDS);
                    if (res) {
                        System.out.println(Thread.currentThread().getName() + "获取锁成功");
                        if (stock > 0) {
                            stock -= 1;
                        }
                    } else {
                        System.out.println(Thread.currentThread().getName() + "获取锁失败");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        System.out.println("所有线程完成");
        System.out.println(stock);
    }

    @Test
    void testPostProduct() {
        List<CustomAttribute> tempList = new ArrayList<>();

        CustomAttribute customAttribute = new CustomAttribute();
        customAttribute.setLabel("颜色");
        customAttribute.setValue(Arrays.asList("红色", "蓝色", "黑色"));
        tempList.add(customAttribute);

        CustomAttribute customAttribute2 = new CustomAttribute();
        customAttribute2.setLabel("尺寸");
        customAttribute2.setValue(Arrays.asList("S", "M", "L"));
        tempList.add(customAttribute2);

        ProductAttribute productAttribute = new ProductAttribute();
        productAttribute.setProductId(1);
        productAttribute.setCustomAttributes(tempList);

        mongoTemplate.insert(productAttribute);
    }

    @Test
    void testGetProduct() {
        Result<ProductVo> product = productService.getProduct(2);
        ProductVo productVo = product.getData();
        System.out.println(productVo.getCustomAttributes().toString());
    }

    @Test
    void insertCoupon() {
        // 根据SQL数据创建10个优惠券对象并插入
        String[] names = {
            "春季大促满减券", "超级购物节专属券", "新用户专享85折", "日常通用优惠券",
            "会员专享9折券", "618预热大额券", "周年庆特惠券", "周末狂欢优惠券",
            "限时秒杀75折", "通用小额优惠券"
        };

        String[] types = {"1", "1", "2", "1", "2", "1", "2", "1", "2", "1"};

        BigDecimal[] discounts = {
            new BigDecimal("20.00"), new BigDecimal("50.00"), new BigDecimal("8.50"),
            new BigDecimal("10.00"), new BigDecimal("9.00"), new BigDecimal("80.00"),
            new BigDecimal("8.80"), new BigDecimal("30.00"), new BigDecimal("7.50"),
            new BigDecimal("15.00")
        };

        Date[] startTimes = {
            java.sql.Timestamp.valueOf("2025-12-05 00:00:00"),
            java.sql.Timestamp.valueOf("2025-12-10 00:00:00"),
            java.sql.Timestamp.valueOf("2025-12-01 00:00:00"),
            java.sql.Timestamp.valueOf("2025-12-15 00:00:00"),
            java.sql.Timestamp.valueOf("2025-12-20 00:00:00"),
            java.sql.Timestamp.valueOf("2025-12-25 00:00:00"),
            java.sql.Timestamp.valueOf("2026-01-01 00:00:00"),
            java.sql.Timestamp.valueOf("2026-01-05 00:00:00"),
            java.sql.Timestamp.valueOf("2026-01-10 10:00:00"),
            java.sql.Timestamp.valueOf("2026-01-15 00:00:00")
        };

        Date[] endTimes = {
            java.sql.Timestamp.valueOf("2025-12-20 23:59:59"),
            java.sql.Timestamp.valueOf("2025-12-25 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-15 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-05 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-10 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-20 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-25 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-15 23:59:59"),
            java.sql.Timestamp.valueOf("2026-01-10 22:00:00"),
            java.sql.Timestamp.valueOf("2026-02-01 23:59:59")
        };

        Integer[] stocks = {1000, 500, 9999, 5000, 2000, 300, 10000, 800, 100, 3000};

        for (int i = 0; i < 10; i++) {
            Coupon coupon = new Coupon();
            coupon.setName(names[i]);
            coupon.setType(types[i]);
            coupon.setDiscount(discounts[i]);
            coupon.setStartTime(startTimes[i]);
            coupon.setEndTime(endTimes[i]);
            coupon.setStock(stocks[i]);

            // 调用 insert 方法插入优惠券
            couponService.insertCoupon(coupon);
        }
    }
}

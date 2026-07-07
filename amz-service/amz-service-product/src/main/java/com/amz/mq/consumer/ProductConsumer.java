package com.amz.mq.consumer;

import com.amz.constant.MqConstant;
import com.amz.model.pojo.Product;
import com.amz.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ProductConsumer {

    @Autowired
    private ProductService productService;

    @RabbitListener(queues = MqConstant.UPDATE_PRODUCT_STOCK_QUEUE)
    public void onMessage(Integer productId){
        log.info("更新商品{}的库存", productId);
        Product product = productService.selectById(productId);
        // 添加库存负数检查
        if (product.getStock() > 0) {
            product.setStock(product.getStock() - 1);
            productService.updateById(product);
        } else {
            log.warn("商品{}库存不足，无法扣减", productId);
        }
    }

}

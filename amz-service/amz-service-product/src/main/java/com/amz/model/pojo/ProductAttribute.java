package com.amz.model.pojo;

import lombok.Data;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document("amz_product_attribute")
public class ProductAttribute {

    @Id
    private ObjectId id;

    /**
     * 属性对应的商品id
     */
    private Integer productId;

    /**
     * 自定义属性
     */
    private List<CustomAttribute> customAttributes;
}

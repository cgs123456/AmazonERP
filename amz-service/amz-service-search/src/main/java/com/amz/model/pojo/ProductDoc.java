package com.amz.model.pojo;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.io.Serializable;

/**
 * 商品 ES 文档（替代原 Note 索引）。
 * <p>
 * 索引名 amz_product，支持 BM25 文本检索 + kNN 向量检索混合搜索。
 */
@Data
@Document(indexName = "amz_product")
public class ProductDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Field(type = FieldType.Long)
    private Long id;

    /** 商品标题 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /** 商品描述 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /** AI 摘要（用于增强检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String summary;

    /** 商品图片 URL */
    @Field(type = FieldType.Keyword, index = false)
    private String image;

    /** 价格（美元） */
    @Field(type = FieldType.Double)
    private Double price;

    /** SKU */
    @Field(type = FieldType.Keyword)
    private String sku;

    /** 所属店铺 ID */
    @Field(type = FieldType.Long)
    private Long shopId;

    /** 所属用户 ID（发布者） */
    @Field(type = FieldType.Integer)
    private Integer userId;

    /** 向量嵌入（kNN 检索用） */
    @Field(type = FieldType.Dense_Vector, dims = 1024)
    private float[] embedding;
}

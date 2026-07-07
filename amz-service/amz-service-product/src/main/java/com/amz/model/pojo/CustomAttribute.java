package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CustomAttribute implements Serializable {

    /**
     * 自定义标签
     */
    private String label;

    /**
     * 标签对应的数据
     */
    private List<String> value;
}

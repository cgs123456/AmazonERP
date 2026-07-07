package com.amz.model.dto;

import com.amz.model.pojo.CustomAttribute;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BuyDto implements Serializable {
    private Integer productId;
    private List<CustomAttribute> selectAttributes;
    private Integer userId;
}

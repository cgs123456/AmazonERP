package com.amz.model.vo;

import com.amz.model.pojo.User;
import lombok.Data;

@Data
public class UserVo {
    private User user;

    /**
     * 年龄
     */
    private Integer age;
}

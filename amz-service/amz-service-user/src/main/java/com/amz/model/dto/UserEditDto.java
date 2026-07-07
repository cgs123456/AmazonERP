package com.amz.model.dto;

import lombok.Data;

@Data
public class UserEditDto {
    private String nickname;
    private String image;
    private String signature;
    private String sex;
    private String birthday;
    private String address;
    private String school;
    private String identity;
}

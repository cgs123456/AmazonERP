package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("amz_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 手机号
     */
    @TableField("phone")
    private String phone;

    /**
     * 昵称
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 头像
     */
    @TableField("image")
    private String image;

    /**
     * 性别
     */
    @TableField("sex")
    private String sex;

    /**
     * 生日
     */
    @TableField("birthday")
    private String birthday;

    /**
     * 地区
     */
    @TableField("address")
    private String address;

    /**
     * 角色：ADMIN/OPERATOR/VIEWER，DB 默认 VIEWER；V1 迁移内建该列。
     */
    @TableField("role")
    private String role;

    /**
     * 账号状态：1=正常 0=禁用（V4 新增）。
     * 禁用后登录被拒、已签发 token 在下一次请求即失效（状态查询走本表）。
     */
    private Integer status;

    /**
     * 创建时间（V1 迁移内建列 create_time）。
     * <p>
     * 此前该列在 DDL 与前端契约（UserRow.createTime、用户管理页「创建时间」列）里都有，
     * 唯独 pojo 未声明——列表接口从不返回它，前端那一列恒为「—」。
     * 只读展示字段，不参与写入（默认值由 DB 的 CURRENT_TIMESTAMP 提供）。
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}

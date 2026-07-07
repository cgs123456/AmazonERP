package com.amz.model.vo;

import com.amz.model.pojo.Note;
import com.amz.model.pojo.User;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class NoteVo extends Note {

    /**
     * 发布笔记用户信息
     */
    private User user;

    /**
     * 处理后的时间
     */
    private String dealTime;

    /**
     * 距离
     */
    private String distance;

    /**
     * 评论数
     */
    private Integer comment;

}

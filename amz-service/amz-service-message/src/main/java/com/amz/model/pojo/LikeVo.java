package com.amz.model.pojo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LikeVo {
    private Like like;
    private Note note;
    private User user;
}

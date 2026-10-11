package com.amz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 店铺下拉选项（{@code UserVo.shops} 的元素）。
 * <p>
 * 只带 id 与名称：这是要写进 localStorage 并展示在顶栏下拉里的东西，
 * 带任何凭证或卖家身份信息都是把敏感物推到浏览器。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopOptionVo {

    private Long id;

    private String name;
}

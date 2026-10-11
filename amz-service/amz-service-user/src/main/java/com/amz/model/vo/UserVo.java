package com.amz.model.vo;

import com.amz.model.pojo.User;
import lombok.Data;

import java.util.List;

@Data
public class UserVo {
    private User user;

    /**
     * 年龄
     */
    private Integer age;

    /**
     * 当前用户被授权的店铺（前端店铺选择器的真实数据源）。
     * <p>
     * 此前 {@code /user/getInfo} 只返回 user 与 age，前端拿不到任何店铺信息，
     * 只能在 {@code main.ts} 里写死「Shop A/B/C」。补这个字段后，
     * 登录 → getInfo → 写入 localStorage 是一条完整链路，不再需要编造数据。
     * <p>
     * 只放 id 与名称：凭证状态由 {@code GET /user/shop/list} 单独提供
     * （那里要跨库查 amz_spapi，不适合塞进每个请求的 getInfo）。
     */
    private List<ShopOptionVo> shops;
}

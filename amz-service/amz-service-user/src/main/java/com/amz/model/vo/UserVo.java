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

    /**
     * 当前选中店铺上的**有效角色**（店铺级优先、全局兜底，口径见
     * {@code UserContext.resolveRole}）。
     * <p>
     * 前端此前读 {@code user.role}（全局角色）来决定写入口的显隐，而鉴权早已按店铺判定——
     * 于是「店 A 是 OPERATOR、店 B 是 VIEWER」的用户在店 B 里看到的是 OPERATOR 的界面：
     * <b>按钮亮着，点下去却报无权限</b>。本字段让前端与后端看同一个值。
     * <p>
     * 取的是请求头 {@code shopId} 指向的那家店；未携带时回落全局角色，与后端一致。
     */
    private String effectiveRole;
}

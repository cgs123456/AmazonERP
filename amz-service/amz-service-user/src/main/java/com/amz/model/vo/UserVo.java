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
     * {@link #shops} 是否为一次**可信**的查询结果。
     * <p>
     * 为什么需要它：查店铺可能失败（DB 抖动、凭证列解密异常等），而前面的 catch 只会
     * 打一句 warn 后继续返回——此时 {@code shops} 是 null，与「该账号确实没有店铺」
     * （空数组）在响应上**完全同形**。前端据此写 localStorage，这三种情况会被读成同一种：
     * <ul>
     *   <li>{@code true} + 空数组 —— 确实没店，应提示「无可用店铺」；</li>
     *   <li>{@code false} + null —— 没查到，此时写空数组会把「查失败」误判成「没店」；
     *       更糟的是用户上一次的旧列表会被清掉，接着所有带 shopId 的请求全部越权失败。</li>
     * </ul>
     * 实测过这个混淆的代价：{@code resolveShopOptions} 因凭证列解密异常被吞掉异常，
     * 前端店铺选择器空白，排查时只能从「登录后没店铺」这个与根因无关的症状反推。
     * <p>
     * 老前端不读本字段，行为与改动前一致（把 null/空数组当没店）。
     */
    private Boolean shopsLoaded;

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

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { isShopsLoadTrusted } from '../utils/shop'

/**
 * shopsLoaded=false 时不得清空本地店铺列表。
 *
 * <b>存在动因</b>：后端 `/user/getInfo` 的 `shops` 字段有三种含义曾挤在一起——
 * 查到店铺、查到但没店铺、查询失败。第三种此前与第二种同形（都是空/null），
 * 前端据此写 localStorage，会把「查失败」误判成「该账号没有店铺」并清空列表。
 * 后果是所有带 shopId 的请求带着后端已不认可的 id 发出去，整站变成大片越权报错。
 *
 * 后端已补 `shopsLoaded` 显式区分（false = 没查到，不是没店）。
 * 这里直接测**真身** `isShopsLoadTrusted`，不复刻一份判定逻辑——那是自己喂自己。
 *
 * 实测过的故障链：amz_shop 凭证列解密异常 → selectList 整行抛错 →
 * 后端 shops=null → 前端清空 → 用户看到「登录后没有店铺」，
 * 而真正的报错藏在服务端一句 warn 里。
 */
describe('isShopsLoadTrusted：只有 false 代表「这次没查到」', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    localStorage.clear()
  })

  it('false → 不可信（后端这次查失败，本地列表必须保留）', () => {
    expect(isShopsLoadTrusted(false)).toBe(false)
  })

  it('true → 可信（含空数组，代表确实没店）', () => {
    expect(isShopsLoadTrusted(true)).toBe(true)
  })

  it('undefined（老后端无该字段）→ 可信，不改变既有行为', () => {
    // 渐进发布：后端没上线该字段时前端不能把用户店铺全清空
    expect(isShopsLoadTrusted(undefined)).toBe(true)
  })

  it('false 之外的假值（null / 0 / 空串 / "false" 字符串）都不当成失败', () => {
    // 后端类型是 Boolean，JSON 序列化只会是 true/false/缺失。
    // 这里的反向断言是防止有人把判定改成 `!shopsLoaded`——
    // 那会把 null/undefined/0 也当失败，误清正常用户的店铺。
    ;[null, 0, '', 'false', Number.NaN].forEach((v) => {
      expect(isShopsLoadTrusted(v)).toBe(true)
    })
  })
})

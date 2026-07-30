import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import {
  getCurrentShopId,
  setCurrentShopId,
  getShops,
  setShops,
  type ShopOption
} from '../utils/shop'

describe('店铺工具 shop.ts', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  describe('setShops / getShops 读写', () => {
    it('setShops 写入后 getShops 应读回相同列表', () => {
      const shops: ShopOption[] = [
        { id: '1', name: 'Shop A (US)' },
        { id: '2', name: 'Shop B (UK)' }
      ]
      setShops(shops)
      expect(getShops()).toEqual(shops)
    })

    it('空列表写入后应能读回空数组', () => {
      setShops([])
      expect(getShops()).toEqual([])
    })

    it('未写入任何数据时 getShops 应返回空数组', () => {
      expect(getShops()).toEqual([])
    })

    it('数字 id 应被保留', () => {
      setShops([{ id: 100, name: '数字店铺' }])
      const result = getShops()
      expect(result.length).toBe(1)
      expect(result[0].id).toBe(100)
    })
  })

  describe('getShops 空值回退与容错', () => {
    it('JSON 解析失败时应返回空数组', () => {
      localStorage.setItem('shops', '{not valid json')
      expect(getShops()).toEqual([])
    })

    it('存储非数组 JSON 时应返回空数组', () => {
      localStorage.setItem('shops', JSON.stringify({ id: 1, name: 'x' }))
      expect(getShops()).toEqual([])
    })

    it('应过滤掉缺少 id 字段的非法条目', () => {
      localStorage.setItem(
        'shops',
        JSON.stringify([
          { id: '1', name: 'OK' },
          { name: '无 id' },
          null,
          'string-item',
          { id: '2', name: 'OK2' }
        ])
      )
      const result = getShops()
      expect(result.length).toBe(2)
      expect(result[0].id).toBe('1')
      expect(result[1].id).toBe('2')
    })

    it('条目缺少 name 时应回退为 id 字符串', () => {
      localStorage.setItem(
        'shops',
        JSON.stringify([{ id: '7' }])
      )
      const result = getShops()
      expect(result.length).toBe(1)
      expect(result[0].name).toBe('7')
    })
  })

  describe('setCurrentShopId / getCurrentShopId 读写', () => {
    it('setCurrentShopId 写入后 getCurrentShopId 应读回相同字符串', () => {
      setCurrentShopId('5')
      expect(getCurrentShopId()).toBe('5')
    })

    it('数字 id 应被转换为字符串存储', () => {
      setCurrentShopId(99)
      expect(localStorage.getItem('current_shop_id')).toBe('99')
      expect(getCurrentShopId()).toBe('99')
    })

    it('未设置且无 shops 时 getCurrentShopId 应返回空字符串', () => {
      expect(getCurrentShopId()).toBe('')
    })
  })

  describe('getCurrentShopId 空值回退到 shops 列表', () => {
    it('无 current_shop_id 但 shops 非空时应回退到第一个并写入', () => {
      setShops([
        { id: '3', name: 'Shop C' },
        { id: '1', name: 'Shop A' }
      ])
      const id = getCurrentShopId()
      expect(id).toBe('3')
      // 回退值应被持久化，后续读取直接命中 localStorage
      expect(localStorage.getItem('current_shop_id')).toBe('3')
    })

    it('回退后再次调用应直接返回已写入的 current_shop_id', () => {
      setShops([{ id: 7, name: '数字回退' }])
      expect(getCurrentShopId()).toBe('7')
      // 第二次调用不再依赖 shops 列表
      expect(getCurrentShopId()).toBe('7')
    })

    it('已有 current_shop_id 时不应回退到 shops', () => {
      setShops([{ id: '1', name: 'Shop A' }])
      setCurrentShopId('99')
      expect(getCurrentShopId()).toBe('99')
    })
  })
})

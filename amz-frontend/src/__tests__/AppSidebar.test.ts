import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const { pushMock, routeState } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  routeState: { path: '/connectors' }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  useRoute: () => routeState
}))

import AppSidebar from '../components/AppSidebar.vue'

describe('AppSidebar 连接器入口', () => {
  beforeEach(() => {
    pushMock.mockClear()
    routeState.path = '/connectors'
  })

  it('展示连接器状态入口，当前路由高亮并可跳转', async () => {
    const wrapper = mount(AppSidebar, {
      global: { stubs: { Icon: true } }
    })

    const item = wrapper.findAll('.nav-item').find((node) => node.text().includes('连接器状态'))
    expect(item).toBeTruthy()
    expect(item!.classes()).toContain('active')

    await item!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/connectors')
  })
})

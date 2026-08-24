import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AuthSession, AuthUser, UserRole } from '@/types'
import { apiMutate } from '@/services/api'
import { demoUsers } from '@/services/demo'
import { mapAuthUser } from '@/services/mappers'

export const roleMeta: Record<UserRole, { label: string; short: string; description: string }> = {
  ADMIN: { label: '系统管理员', short: '管理员', description: '维护权限、数据接入与审计' },
  BUYER: { label: '采购协同人员', short: '采购', description: '负责需求、订单、收货与对账' },
  SUPPLIER: { label: '供应商', short: '供应商', description: '查看订单并协同交付' },
  MANAGER: { label: '企业管理人员', short: '管理', description: '查看看板、风险与审批' },
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(loadSession())
  const loading = ref(false)
  const errorMessage = ref('')

  const user = computed(() => session.value?.user || null)
  const isAuthenticated = computed(() => Boolean(session.value?.token))
  const role = computed(() => session.value?.user.role || 'BUYER')
  const roleInfo = computed(() => roleMeta[role.value])

  async function login(username: string, password: string) {
    loading.value = true
    errorMessage.value = ''
    try {
      const result = await apiMutate<{ accessToken?: string; token?: string; tokenType?: string; expiresIn?: number; user: unknown }>(
        { method: 'POST', url: '/auth/login', data: { username, password } },
        () => {
          const matched = demoUsers.find((item) => item.username === username && item.password === password)
          if (!matched) throw new Error('演示账号或密码不正确')
          return { accessToken: `demo-token-${matched.username}`, user: matched.user }
        },
      )
      const token = result.accessToken || result.token
      if (!token || !result.user) throw new Error('登录响应缺少 accessToken 或 user')
      const sessionResult: AuthSession = { token, user: mapAuthUser(result.user), tokenType: result.tokenType, expiresIn: result.expiresIn }
      session.value = sessionResult
      localStorage.setItem('luna_access_token', sessionResult.token)
      localStorage.setItem('luna_session', JSON.stringify(sessionResult))
      return sessionResult
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '登录失败，请检查账号与密码。'
      throw error
    } finally {
      loading.value = false
    }
  }

  function switchRole(nextRole: UserRole) {
    const current = session.value
    if (!current) return
    const demo = demoUsers.find((item) => item.user.role === nextRole)
    if (demo) {
      session.value = { ...current, token: current.token.startsWith('demo-') ? `demo-token-${demo.username}` : current.token, user: demo.user }
      localStorage.setItem('luna_session', JSON.stringify(session.value))
    } else {
      session.value = { ...current, user: { ...current.user, role: nextRole } }
      localStorage.setItem('luna_session', JSON.stringify(session.value))
    }
  }

  function logout() {
    session.value = null
    localStorage.removeItem('luna_access_token')
    localStorage.removeItem('luna_session')
  }

  return { session, user, role, roleInfo, isAuthenticated, loading, errorMessage, login, switchRole, logout }
})

function loadSession(): AuthSession | null {
  try {
    const raw = localStorage.getItem('luna_session')
    return raw ? (JSON.parse(raw) as AuthSession) : null
  } catch {
    return null
  }
}

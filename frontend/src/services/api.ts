import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import { readonly, ref } from 'vue'
import type { ApiEnvelope } from '@/types'
import { camelizeKeys } from '@/services/mappers'

const apiBaseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const demoFallbackEnabled = import.meta.env.VITE_ENABLE_DEMO_FALLBACK === 'true'

export const apiClient = axios.create({
  baseURL: apiBaseURL,
  timeout: 8000,
  headers: { 'Content-Type': 'application/json' },
})

const demoMode = ref(localStorage.getItem('luna_demo_mode') === '1')
let lastApiError = ''
let redirectingToLogin = false

export const demoModeState = readonly(demoMode)

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('luna_access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<unknown>) => {
    lastApiError = extractApiError(error)
    if (error.response?.status === 401) handleUnauthorized(error)
    return Promise.reject(error)
  },
)

function handleUnauthorized(error: AxiosError<unknown>) {
  if (typeof window === 'undefined') return
  const requestPath = String(error.config?.url || '')
  const onLoginPage = window.location.pathname === '/login'
  const isLoginRequest = requestPath.includes('/auth/login')
  if (onLoginPage || isLoginRequest || redirectingToLogin) return

  localStorage.removeItem('luna_access_token')
  localStorage.removeItem('luna_session')
  localStorage.removeItem('luna_demo_mode')
  redirectingToLogin = true

  const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`
  const loginUrl = `/login?reason=expired&redirect=${encodeURIComponent(returnTo)}`
  window.location.replace(loginUrl)
}

export function isDemoMode() { return demoMode.value }
export function getLastApiError() { return lastApiError }
export function setDemoMode(value: boolean) { demoMode.value = value; if (value) localStorage.setItem('luna_demo_mode', '1'); else localStorage.removeItem('luna_demo_mode') }
export function isDemoFallbackEnabled() { return demoFallbackEnabled }

/** Keep backend contract alignment in one place. Views use semantic paths. */
export function normalizeApiPath(path?: string) {
  if (!path || path.startsWith('http')) return path
  const exact: Record<string, string> = {
    '/data-import/batches': '/imports',
    '/forecast/14d': '/intelligence/forecast-runs',
    '/ai-parse/preview': '/intelligence/parse-previews',
  }
  if (exact[path]) return exact[path]
  const mappings: Array<[RegExp, string]> = [
    [/^\/data-import(\/|$)/, '/imports$1'],
    [/^\/(suppliers|materials|warehouses|inventory)(\/|$)/, '/master-data/$1$2'],
    [/^\/purchase-demands(\/|$)/, '/procurement/demands$1'],
    [/^\/purchase-plans(\/|$)/, '/procurement/plans$1'],
    [/^\/purchase-orders(\/|$)/, '/procurement/orders$1'],
    [/^\/(delivery-notices|receipts|reconciliations)(\/|$)/, '/collaboration/$1$2'],
    [/^\/forecast(\/|$)/, '/intelligence/forecast-runs$1'],
    [/^\/warnings(\/|$)/, '/system/warnings$1'],
    [/^\/ai-parse(\/|$)/, '/intelligence/parse-previews$1'],
    [/^\/audit(\/|$)/, '/system/audit-logs$1'],
  ]
  for (const [pattern, replacement] of mappings) if (pattern.test(path)) return path.replace(pattern, replacement)
  return path
}

function canUseDemoFallback(error: unknown) {
  if (!axios.isAxiosError(error)) return true
  return !error.response || error.response.status >= 500
}

function hasHeader(headers: AxiosRequestConfig['headers'], name: string) {
  return headers ? Object.keys(headers).some((key) => key.toLowerCase() === name.toLowerCase()) : false
}

function idempotencyKey() {
  return globalThis.crypto?.randomUUID?.() || `lm-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function preparedConfig(config: AxiosRequestConfig): AxiosRequestConfig {
  const method = (config.method || 'GET').toUpperCase()
  const headers = { ...(config.headers || {}) } as Record<string, unknown>
  if (method === 'POST' && !hasHeader(config.headers, 'Idempotency-Key') && !String(config.url || '').includes('/auth/login')) headers['Idempotency-Key'] = idempotencyKey()
  return { ...config, url: normalizeApiPath(config.url), headers: headers as AxiosRequestConfig['headers'] }
}

function unwrap<T>(payload: unknown): T {
  const normalized = camelizeKeys(payload)
  if (normalized && typeof normalized === 'object' && 'data' in normalized) return (normalized as ApiEnvelope<T>).data as T
  return normalized as T
}

export async function apiRequest<T>(config: AxiosRequestConfig, fallback?: () => T | Promise<T>): Promise<T> {
  try {
    const response = await apiClient.request<unknown>(preparedConfig(config))
    setDemoMode(false)
    return unwrap<T>(response.data)
  } catch (error) {
    if (fallback && demoFallbackEnabled && canUseDemoFallback(error)) { setDemoMode(true); return fallback() }
    throw error
  }
}

export async function apiMutate<T>(config: AxiosRequestConfig, fallback?: () => T | Promise<T>): Promise<T> {
  try {
    const response = await apiClient.request<unknown>(preparedConfig(config))
    setDemoMode(false)
    return unwrap<T>(response.data)
  } catch (error) {
    if (fallback && demoFallbackEnabled && canUseDemoFallback(error)) { setDemoMode(true); return fallback() }
    throw error
  }
}

export async function apiList<T>(config: AxiosRequestConfig, mapper?: (value: unknown) => T, fallback?: () => unknown[] | Promise<unknown[]>): Promise<{ records: T[]; total: number }> {
  const payload = await apiRequest<unknown>(config, fallback)
  const raw: unknown[] = Array.isArray(payload) ? payload : payload && typeof payload === 'object' && Array.isArray((payload as Record<string, unknown>).records) ? (payload as Record<string, unknown>).records as unknown[] : payload && typeof payload === 'object' && Array.isArray((payload as Record<string, unknown>).items) ? (payload as Record<string, unknown>).items as unknown[] : []
  const records = mapper ? raw.map(mapper) : raw as T[]
  const total = payload && typeof payload === 'object' && typeof (payload as Record<string, unknown>).total === 'number' ? Number((payload as Record<string, unknown>).total) : records.length
  return { records, total }
}

export function extractApiError(error: unknown) {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as Record<string, unknown> | undefined
    const nested = data?.error && typeof data.error === 'object' ? data.error as Record<string, unknown> : undefined
    const message = nested?.message || data?.message || error.message
    const code = nested?.code || data?.code
    const requestId = data?.requestId
    return [code ? `[${code}]` : '', message || '后端服务请求失败', requestId ? `（请求号 ${requestId}）` : ''].filter(Boolean).join(' ')
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试。'
}

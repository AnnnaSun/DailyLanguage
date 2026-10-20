export interface CurrentUser { userId: string }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code)
  }
}

const errorCodes = new Set([
  'UNAUTHENTICATED', 'INVALID_CREDENTIALS', 'AUTHENTICATION_UNAVAILABLE',
  'TOO_MANY_LOGIN_ATTEMPTS',
])

async function request(path: string, options: RequestInit = {}): Promise<Response> {
  let response: Response
  try {
    response = await fetch(path, {
      ...options, credentials: 'same-origin', signal: AbortSignal.timeout(10_000),
    })
  } catch {
    // 不把网络异常或请求正文带入界面、日志和异常文本。
    throw new ApiError(0, 'NETWORK_UNAVAILABLE')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const code = errorCodes.has(body?.code) ? body.code : 'HTTP_ERROR'
    throw new ApiError(response.status, code)
  }
  return response
}

export async function getCurrentUser(): Promise<CurrentUser | null> {
  let response: Response
  try {
    response = await request('/api/auth/me')
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && error.code === 'UNAUTHENTICATED') return null
    throw error
  }
  const user = await response.json().catch(() => null)
  if (typeof user?.userId !== 'string' || !user.userId) throw new ApiError(0, 'INVALID_RESPONSE')
  return { userId: user.userId }
}

async function csrfHeaders(): Promise<Headers> {
  // 复用公开 GET 触发 SPA token cookie；每次写操作重新读取，避免认证后沿用旧 token。
  await request('/api/auth/registration')
  const cookie = document.cookie.split(';').map(value => value.trim())
    .find(value => value.startsWith('XSRF-TOKEN='))
  if (!cookie) throw new ApiError(403, 'CSRF_UNAVAILABLE')
  try {
    return new Headers({ 'X-XSRF-TOKEN': decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) })
  } catch {
    throw new ApiError(403, 'CSRF_UNAVAILABLE')
  }
}

export async function login(email: string, password: string): Promise<void> {
  const headers = await csrfHeaders()
  headers.set('Content-Type', 'application/x-www-form-urlencoded')
  await request('/api/auth/login', {
    method: 'POST', headers, body: new URLSearchParams({ email, password }),
  })
}

export async function logout(): Promise<void> {
  await request('/api/auth/logout', { method: 'POST', headers: await csrfHeaders() })
}

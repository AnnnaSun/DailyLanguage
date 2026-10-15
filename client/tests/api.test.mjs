import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

// 使用项目已有 TypeScript compiler 在内存中加载 production module，不增加 test dependency。
const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
})
const api = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)
const originalFetch = globalThis.fetch
let requests
let responses
const json = (body, status = 200) => new Response(JSON.stringify(body), { status })

beforeEach(() => {
  requests = []
  responses = []
  globalThis.document = { cookie: '' }
  globalThis.fetch = async (path, options) => {
    requests.push({ path, ...options })
    assert.equal(options.credentials, 'same-origin')
    assert.ok(options.signal instanceof AbortSignal)
    assert.ok(responses.length, 'unexpected request or automatic retry')
    const next = responses.shift()
    return typeof next === 'function' ? next() : next
  }
})
afterEach(() => {
  globalThis.fetch = originalFetch
  delete globalThis.document
})

test('only the declared unauthenticated result becomes an anonymous identity', async () => {
  responses.push(json({ code: 'UNAUTHENTICATED' }, 401))
  assert.equal(await api.getCurrentUser(), null)
  assert.equal(requests[0].path, '/api/auth/me')
})

test('a generic 401 is not silently treated as a confirmed anonymous identity', async () => {
  responses.push(new Response('', { status: 401 }))
  await assert.rejects(api.getCurrentUser(), { status: 401, code: 'HTTP_ERROR' })
})

test('a service failure cannot become an anonymous or authenticated identity', async () => {
  responses.push(json({ code: 'AUTHENTICATION_UNAVAILABLE' }, 503))
  await assert.rejects(api.getCurrentUser(), { status: 503, code: 'AUTHENTICATION_UNAVAILABLE' })
})

test('invalid success JSON is rejected', async () => {
  responses.push(new Response('<html>upstream failure</html>'))
  await assert.rejects(api.getCurrentUser(), { code: 'INVALID_RESPONSE' })
})

test('an invalid identity shape is rejected', async () => {
  responses.push(json({ userId: null }))
  await assert.rejects(api.getCurrentUser(), { code: 'INVALID_RESPONSE' })
})

test('the identity projection retains only the backend userId', async () => {
  responses.push(json({ userId: 'test-user', extra: 'ignored' }))
  assert.deepEqual(await api.getCurrentUser(), { userId: 'test-user' })
})

test('login bootstraps CSRF then uses the new cookie and form contract', async () => {
  document.cookie = 'XSRF-TOKEN=old-token'
  responses.push(() => {
    document.cookie = 'other=value; XSRF-TOKEN=new%2Dtoken'
    return json({ state: 'PUBLIC' })
  }, new Response(null, { status: 204 }))
  await api.login('example@example.test', 'synthetic-password')
  assert.deepEqual(requests.map(r => r.path), ['/api/auth/registration', '/api/auth/login'])
  const post = requests[1]
  assert.equal(post.method, 'POST')
  assert.equal(post.headers.get('X-XSRF-TOKEN'), 'new-token')
  assert.equal(post.headers.get('Content-Type'), 'application/x-www-form-urlencoded')
  assert.equal(post.body.get('email'), 'example@example.test')
  assert.equal(post.body.get('password'), 'synthetic-password')
})

test('missing CSRF cookie prevents the login POST', async () => {
  responses.push(json({ state: 'PUBLIC' }))
  await assert.rejects(api.login('example@example.test', 'synthetic-password'), { code: 'CSRF_UNAVAILABLE' })
  assert.equal(requests.length, 1)
})

test('an undecodable CSRF cookie prevents the write', async () => {
  document.cookie = 'XSRF-TOKEN=%'
  responses.push(json({ state: 'PUBLIC' }))
  await assert.rejects(api.logout(), { code: 'CSRF_UNAVAILABLE' })
  assert.equal(requests.length, 1)
})

test('a rejected login is not retried', async () => {
  document.cookie = 'XSRF-TOKEN=token'
  responses.push(json({ state: 'PUBLIC' }), json({ code: 'INVALID_CREDENTIALS' }, 401))
  await assert.rejects(api.login('example@example.test', 'synthetic-password'), { code: 'INVALID_CREDENTIALS' })
  assert.equal(requests.length, 2)
})

test('logout reads a rotated CSRF cookie and does not send a credential body', async () => {
  document.cookie = 'XSRF-TOKEN=before-login'
  responses.push(() => {
    document.cookie = 'XSRF-TOKEN=after-login'
    return json({ state: 'PUBLIC' })
  }, new Response(null, { status: 204 }))
  await api.logout()
  assert.equal(requests[1].path, '/api/auth/logout')
  assert.equal(requests[1].headers.get('X-XSRF-TOKEN'), 'after-login')
  assert.equal(requests[1].body, undefined)
})

test('non-JSON CSRF failure stays a safe 403 without retry', async () => {
  document.cookie = 'XSRF-TOKEN=token'
  responses.push(json({ state: 'PUBLIC' }), new Response('<html>forbidden</html>', { status: 403 }))
  await assert.rejects(api.logout(), { status: 403, code: 'HTTP_ERROR' })
  assert.equal(requests.length, 2)
})

test('a write-side 401 remains an error rather than confirmed anonymous success', async () => {
  document.cookie = 'XSRF-TOKEN=token'
  responses.push(json({ state: 'DISABLED' }), json({ code: 'UNAUTHENTICATED' }, 401))
  await assert.rejects(api.logout(), { status: 401, code: 'UNAUTHENTICATED' })
  assert.equal(requests.length, 2)
})

test('rate limiting remains a distinct result', async () => {
  document.cookie = 'XSRF-TOKEN=token'
  responses.push(json({ state: 'PUBLIC' }), json({ code: 'TOO_MANY_LOGIN_ATTEMPTS' }, 429))
  await assert.rejects(api.login('example@example.test', 'synthetic-password'), { status: 429, code: 'TOO_MANY_LOGIN_ATTEMPTS' })
})

test('unrecognized server text is excluded from the error', async () => {
  responses.push(json({ code: 'private-server-detail', message: 'private-message' }, 500))
  await assert.rejects(api.getCurrentUser(), error => {
    assert.equal(error.message, 'HTTP_ERROR')
    assert.equal(error.code, 'HTTP_ERROR')
    assert.equal(JSON.stringify(error).includes('private'), false)
    return true
  })
})

test('transport timeout is safe and never retries a write', async () => {
  document.cookie = 'XSRF-TOKEN=token'
  responses.push(json({ state: 'PUBLIC' }), () => { throw new DOMException('private transport detail', 'TimeoutError') })
  await assert.rejects(api.logout(), { status: 0, code: 'NETWORK_UNAVAILABLE', message: 'NETWORK_UNAVAILABLE' })
  assert.equal(requests.length, 2)
})

/** 隔离浏览器安全回归：假账号与临时加密密钥，不访问真实后端或用户浏览器资料。 */
const assert = require('node:assert/strict');
const http = require('node:http');
const { webcrypto } = require('node:crypto');
const { chromium } = require(process.env.LANGUAGE_LEAN_PLAYWRIGHT_PATH ||
  'C:/Users/83575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const origin = process.env.FRONTEND_SECURITY_URL || 'http://localhost:5188';
const baseline = process.argv.includes('--baseline');
const expectedPassword = 'SecurityFixture12!';
const user = { id: '11111111-1111-4111-8111-111111111111', username: 'security-fixture', email: 'fixture@example.test', roles: ['USER'], mustChangePassword: false };

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const attacker = http.createServer((_req, res) => {
    res.setHeader('Content-Type', 'text/html');
    res.end(`<iframe src="${origin}/login"></iframe>`);
  });
  try {
    await new Promise(resolve => attacker.listen(0, '127.0.0.1', resolve));
    const context = await browser.newContext();
    let signedIn = false, encryptedLogins = 0;
    const keys = new Map();
    await context.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), body = request.postDataJSON();
      let status = 200, result = [];
      if (url.pathname === '/api/v1/auth/me') { status = signedIn ? 200 : 401; result = signedIn ? user : { code: 'AUTHENTICATION_REQUIRED' }; }
      else if (url.pathname === '/api/v1/auth/csrf') result = { headerName: 'X-XSRF-TOKEN', token: 'fixture-csrf' };
      else if (url.pathname === '/api/v1/auth/password-key') {
        const key = webcrypto.getRandomValues(new Uint8Array(32)), keyId = webcrypto.randomUUID();
        keys.set(keyId, { key, path: body.path });
        result = { keyId, algorithm: 'AES-256-GCM', key: Buffer.from(key).toString('base64'), expiresAt: new Date(Date.now() + 120000).toISOString() };
      } else if (url.pathname === '/api/v1/auth/login') {
        assert(!request.postData().includes(expectedPassword), '网络请求不能含明文密码');
        assert.equal(request.headers()['x-xsrf-token'], 'fixture-csrf');
        const issued = keys.get(body.keyId); assert(issued); keys.delete(body.keyId);
        const key = await webcrypto.subtle.importKey('raw', issued.key, 'AES-GCM', false, ['decrypt']);
        const decoded = await webcrypto.subtle.decrypt({ name: 'AES-GCM', iv: Buffer.from(body.nonce, 'base64'),
          additionalData: Buffer.from(`password-v1|${body.keyId}|${issued.path}`), tagLength: 128 }, key, Buffer.from(body.ciphertext, 'base64'));
        assert.equal(JSON.parse(Buffer.from(decoded).toString()).password, expectedPassword);
        issued.key.fill(0); signedIn = true; encryptedLogins++; result = { authenticated: true };
      } else if (url.pathname.includes('/dictionary')) result = { items: [], total: 0, page: 0, size: 20 };
      await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(result) });
    });
    const page = await context.newPage();
    page.on('pageerror', error => console.error('浏览器脚本异常:', error.message));
    const response = await page.goto(origin + '/login');
    const active = () => page.locator('.ion-page:not(.ion-page-hidden)');
    await active().locator('.login ion-input').nth(1).waitFor();
    await active().locator('.login ion-input').nth(1).evaluate((input, value) => {
      input.value = value; input.dispatchEvent(new CustomEvent('ionInput', { detail: { value }, bubbles: true }));
    }, expectedPassword);
    await active().locator('a[href="/forgot-password"]').click();
    await page.waitForURL('**/forgot-password');
    await page.goBack();
    await page.waitForURL('**/login');
    const retainedLogin = await active().locator('.login ion-input').nth(1).evaluate(input => input.value);
    assert.equal(retainedLogin, baseline ? expectedPassword : '', '离开缓存的登录页后密码应被清除');

    await active().locator('.login ion-input').evaluateAll((inputs, values) => inputs.forEach((input, index) => {
      input.value = values[index]; input.dispatchEvent(new CustomEvent('ionInput', { detail: { value: values[index] }, bubbles: true }));
    }), ['security-fixture', expectedPassword]);
    await active().locator('.login form').filter({ has: page.locator('ion-input') }).evaluate(form => form.requestSubmit());
    await page.waitForURL(origin + '/');
    await active().locator('.password-form').waitFor();
    await active().locator('.password-form ion-input').evaluateAll(inputs => inputs.forEach(input => {
      input.value = 'DiscardMe12!'; input.dispatchEvent(new CustomEvent('ionInput', { detail: { value: input.value }, bubbles: true }));
    }));
    await active().locator('a[href="/dictionary"]').click();
    await page.waitForURL('**/dictionary');
    await page.goBack();
    await page.waitForURL(origin + '/');
    const retainedChange = await active().locator('.password-form ion-input').evaluateAll(inputs => inputs.map(input => input.value));
    assert.deepEqual(retainedChange, Array(3).fill(baseline ? 'DiscardMe12!' : ''), '缓存首页不能保留改密码输入');
    assert.equal(encryptedLogins, 1);

    if (!baseline) {
      const headers = response.headers();
      assert.equal(headers['x-frame-options'], 'DENY');
      assert(headers['content-security-policy'].includes("frame-ancestors 'none'"));
      assert.equal(headers['referrer-policy'], 'no-referrer');
      await page.evaluate(() => {
        const script = document.createElement('script'); script.textContent = 'window.securityInlineRan = true'; document.body.append(script);
      });
      assert.equal(await page.evaluate(() => window.securityInlineRan), undefined, 'CSP 应拒绝新增内联脚本');
    }
    const embedded = await context.newPage();
    signedIn = false;
    await embedded.goto(`http://127.0.0.1:${attacker.address().port}`);
    if (baseline) await embedded.frameLocator('iframe').locator('.login').waitFor();
    else {
      await embedded.waitForTimeout(500);
      assert.equal(await embedded.frameLocator('iframe').locator('.login').count(), 0, '跨站 iframe 不能显示应用');
    }
    // 应用壳缓存必须继续支持离线启动，且不缓存任何个人 API。
    await page.evaluate(() => navigator.serviceWorker.ready);
    await page.waitForFunction(() => !!navigator.serviceWorker.controller);
    const apiCached = await page.evaluate(async () => {
      for (const name of await caches.keys()) for (const request of await (await caches.open(name)).keys())
        if (new URL(request.url).pathname.startsWith('/api/')) return true;
      return false;
    });
    assert.equal(apiCached, false);
    const redirectedShell = await page.evaluate(async () => {
      for (const name of await caches.keys()) {
        const shell = await (await caches.open(name)).match('/index.html');
        if (shell) return shell.redirected;
      }
      throw Error('静态入口未缓存');
    });
    await page.unrouteAll(); // 接下来的离线加载由真实 Service Worker 提供应用壳。
    await context.unrouteAll();
    await context.setOffline(true);
    await page.goto(origin + '/login?reauth=1');
    await active().locator('.login').waitFor();
    console.log(JSON.stringify({ mode: baseline ? 'baseline-reproduced' : 'fixed-passed', passwordPages: 2,
      encryptedLogins, embedding: baseline ? 'allowed' : 'blocked', inlineScript: baseline ? 'not-tested' : 'blocked', offlineShell: 'passed', redirectedShell, personalApiCache: false }));
    await context.close();
  } finally { await browser.close(); await new Promise(resolve => attacker.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });

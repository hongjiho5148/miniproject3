const BASE = process.env.BASE || 'http://localhost:8090';

async function request(path, { method = 'GET', cookie, body } = {}) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (cookie) headers['Cookie'] = cookie;
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  return res;
}

async function login(loginId, password = '1234') {
  const res = await request('/api/auth/login', { method: 'POST', body: { loginId, password } });
  if (!res.ok) throw new Error(`login failed (${loginId}): ${res.status}`);
  const setCookies = res.headers.getSetCookie();
  return setCookies.map((c) => c.split(';')[0]).join('; ');
}

async function join(loginId, role = 'USER') {
  const res = await request('/api/auth/join', {
    method: 'POST',
    body: {
      loginId,
      password: '1234',
      name: 'loadtest',
      email: `${loginId}@loadtest.com`,
      phone: '010-0000-0000',
      role,
    },
  });
  if (!res.ok) throw new Error(`join failed (${loginId}): ${res.status}`);
}

async function createUserWithCookie(loginId) {
  await join(loginId);
  return login(loginId);
}

async function issuePayment(cookie, productId = 1, amount = 100000) {
  const res = await request('/api/payments/issue', {
    method: 'POST',
    cookie,
    body: { productId, productName: 'loadtest', depositedAmount: amount },
  });
  if (!res.ok) throw new Error(`issue failed: ${res.status} ${await res.text()}`);
  return (await res.json()).payUuid;
}

async function reportDeposit(cookie, payUuid) {
  const res = await request(`/api/payments/report-deposit/${payUuid}`, { method: 'POST', cookie });
  if (!res.ok) throw new Error(`report-deposit failed: ${res.status}`);
}

async function myPaymentStatus(cookie, payUuid) {
  const res = await request('/api/payments/history', { cookie });
  const list = await res.json();
  const found = list.find((p) => p.payUuid === payUuid);
  return found ? found.status : 'UNKNOWN';
}

function percentile(sortedArr, p) {
  if (sortedArr.length === 0) return 0;
  const idx = Math.min(sortedArr.length - 1, Math.ceil((p / 100) * sortedArr.length) - 1);
  return sortedArr[idx];
}

module.exports = {
  BASE, request, login, join, createUserWithCookie,
  issuePayment, reportDeposit, myPaymentStatus, percentile,
};

/**
 * 판매자 결제 목록 조회 1회(cache miss)가 몇 개의 SQL을 발생시키는지 확인하기 위한 보조 스크립트.
 * 사용법: node sql-count-probe.js <백엔드 로그 파일 경로> [seedUsers]
 */
const fs = require('fs');
const { execSync } = require('child_process');
const { login, request, createUserWithCookie, issuePayment } = require('./common');

const LOG = process.argv[2];
const SEED_USERS = parseInt(process.argv[3] || '5', 10);
const RUN_ID = Date.now().toString(36);

(async () => {
  const sellerCookie = await login('seller01');
  for (let u = 0; u < SEED_USERS; u++) {
    const cookie = await createUserWithCookie(`sq_${RUN_ID}_${u}`);
    for (let p = 1; p <= 9; p++) await issuePayment(cookie, p);
  }

  execSync('docker exec safepay-local-redis redis-cli del sellerPayments::10', { stdio: 'ignore' });
  const before = fs.readFileSync(LOG, 'utf8').length;
  const res = await request('/api/payments/seller/history', { cookie: sellerCookie });
  const rows = (await res.json()).length;
  await new Promise((r) => setTimeout(r, 1000));
  const delta = fs.readFileSync(LOG, 'utf8').slice(before);

  const count = (re) => (delta.match(re) || []).length;
  console.log(JSON.stringify({
    rows,
    selectFromPayment: count(/from\s+payment p1_0/g),
    selectFromVirtualAccount: count(/from\s+virtual_account va1_0/g),
    selectFromMember: count(/from\s+member m1_0/g),
    selectFromProduct: count(/from\s+product p1_0/g),
    totalHibernateSelects: count(/org\.hibernate\.SQL\s*:\s*\n\s+select/g),
  }, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });

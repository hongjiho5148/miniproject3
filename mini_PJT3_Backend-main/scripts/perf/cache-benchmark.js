/**
 * 판매자 결제 목록 조회(GET /api/payments/seller/history)의 캐시 효과 측정.
 *  - cold: 요청 직전에 Redis 캐시 키를 지워 매번 DB 조회 경로를 타게 함
 *  - warm: 캐시가 채워진 상태에서 반복 조회
 *
 * 사용법: node cache-benchmark.js [seedUsers] [requests]
 * 사전 조건: 백엔드(8090) + MySQL + Redis(docker 컨테이너 safepay-local-redis) 실행
 */
const { execSync } = require('child_process');
const { request, login, createUserWithCookie, issuePayment, percentile } = require('./common');

const SEED_USERS = parseInt(process.argv[2] || '60', 10);
const REQUESTS = parseInt(process.argv[3] || '100', 10);
const RUN_ID = Date.now().toString(36);
const CACHE_KEY = 'sellerPayments::10';

function evictCache() {
  execSync(`docker exec safepay-local-redis redis-cli del ${CACHE_KEY}`, { stdio: 'ignore' });
}

async function timedGet(cookie) {
  const start = process.hrtime.bigint();
  const res = await request('/api/payments/seller/history', { cookie });
  const body = await res.text();
  const ms = Number(process.hrtime.bigint() - start) / 1e6;
  if (!res.ok) throw new Error(`seller/history failed: ${res.status}`);
  return { ms, bytes: Buffer.byteLength(body), count: JSON.parse(body).length };
}

function summarize(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
  return {
    mean: +mean.toFixed(2),
    median: +percentile(sorted, 50).toFixed(2),
    p95: +percentile(sorted, 95).toFixed(2),
    min: +sorted[0].toFixed(2),
    max: +sorted[sorted.length - 1].toFixed(2),
  };
}

(async () => {
  const sellerCookie = await login('seller01');

  // 시드: 사용자 N명이 상품 9종을 각각 1건씩 발급 (회원+상품 단위 중복 발급 락을 피하기 위함)
  for (let u = 0; u < SEED_USERS; u++) {
    const cookie = await createUserWithCookie(`cb_${RUN_ID}_${u}`);
    for (let productId = 1; productId <= 9; productId++) {
      await issuePayment(cookie, productId);
    }
  }

  // 워밍업 후 측정 대상 데이터 규모 확인
  evictCache();
  const probe = await timedGet(sellerCookie);

  const cold = [];
  for (let i = 0; i < REQUESTS; i++) {
    evictCache();
    cold.push((await timedGet(sellerCookie)).ms);
  }

  evictCache();
  await timedGet(sellerCookie); // 캐시 채우기
  const warm = [];
  for (let i = 0; i < REQUESTS; i++) {
    warm.push((await timedGet(sellerCookie)).ms);
  }

  console.log(JSON.stringify({
    runId: RUN_ID,
    payments: probe.count,
    responseBytes: probe.bytes,
    requestsPerCase: REQUESTS,
    coldMs: summarize(cold),
    warmMs: summarize(warm),
    speedupByMedian: +(summarize(cold).median / summarize(warm).median).toFixed(1),
  }, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });

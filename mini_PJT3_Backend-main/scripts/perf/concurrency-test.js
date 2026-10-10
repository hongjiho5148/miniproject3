/**
 * 동시성 검증 스크립트.
 *  A) 같은 결제 건에 승인 요청과 취소 요청을 동시에 발사 (라운드 반복)
 *  B) 같은 결제 건에 승인 요청을 N개 동시에 발사 (라운드 반복)
 *
 * 사용법: node concurrency-test.js [rounds] [parallelApprovals]
 * 사전 조건: 백엔드(8090) + MySQL + Redis 실행, 시드 계정(seller01/1234) 존재
 */
const {
  request, login, createUserWithCookie, issuePayment, reportDeposit, myPaymentStatus,
} = require('./common');

const ROUNDS = parseInt(process.argv[2] || '30', 10);
const PARALLEL = parseInt(process.argv[3] || '20', 10);
const RUN_ID = Date.now().toString(36);

async function prepareDepositedPayment(userCookie, productId) {
  const payUuid = await issuePayment(userCookie, productId);
  await reportDeposit(userCookie, payUuid);
  return payUuid;
}

async function scenarioApproveVsCancel(sellerCookie) {
  const tally = { rounds: ROUNDS, bothSucceeded: 0, oneSucceeded: 0, noneSucceeded: 0, serverError500: 0, inconsistentFinalState: 0 };
  const statusCodes = {};

  for (let i = 0; i < ROUNDS; i++) {
    const userCookie = await createUserWithCookie(`ac_${RUN_ID}_${i}`);
    const payUuid = await prepareDepositedPayment(userCookie, (i % 9) + 1);

    const [approve, cancel] = await Promise.all([
      request(`/api/payments/approve/${payUuid}`, { method: 'POST', cookie: sellerCookie }),
      request(`/api/payments/cancel/${payUuid}`, { method: 'POST', cookie: userCookie }),
    ]);
    statusCodes[`approve:${approve.status}`] = (statusCodes[`approve:${approve.status}`] || 0) + 1;
    statusCodes[`cancel:${cancel.status}`] = (statusCodes[`cancel:${cancel.status}`] || 0) + 1;

    const approveOk = approve.status === 200;
    const cancelOk = cancel.status === 200;
    if (approve.status >= 500 || cancel.status >= 500) tally.serverError500++;

    if (approveOk && cancelOk) tally.bothSucceeded++;
    else if (approveOk || cancelOk) tally.oneSucceeded++;
    else tally.noneSucceeded++;

    const finalStatus = await myPaymentStatus(userCookie, payUuid);
    const consistent =
      (approveOk && !cancelOk && finalStatus === 'PAID') ||
      (cancelOk && !approveOk && finalStatus === 'CANCELLED') ||
      (!approveOk && !cancelOk);
    if (!consistent) tally.inconsistentFinalState++;
  }
  return { tally, statusCodes };
}

// 승인(판매자)과 만료(화면 타이머/스케줄러가 호출하는 expire API)가 같은 결제 건에 동시에 들어오는 경우
async function scenarioApproveVsExpire(sellerCookie) {
  const tally = { rounds: ROUNDS, bothSucceeded: 0, oneSucceeded: 0, serverError500: 0, inconsistentFinalState: 0 };
  const statusCodes = {};

  for (let i = 0; i < ROUNDS; i++) {
    const userCookie = await createUserWithCookie(`ae_${RUN_ID}_${i}`);
    const payUuid = await prepareDepositedPayment(userCookie, (i % 9) + 1);

    const [approve, expire] = await Promise.all([
      request(`/api/payments/approve/${payUuid}`, { method: 'POST', cookie: sellerCookie }),
      request(`/api/payments/${payUuid}/expire`, { method: 'POST', cookie: userCookie }),
    ]);
    statusCodes[`approve:${approve.status}`] = (statusCodes[`approve:${approve.status}`] || 0) + 1;
    statusCodes[`expire:${expire.status}`] = (statusCodes[`expire:${expire.status}`] || 0) + 1;

    const approveOk = approve.status === 200;
    const expireOk = expire.status === 200;
    if (approve.status >= 500 || expire.status >= 500) tally.serverError500++;

    const finalStatus = await myPaymentStatus(userCookie, payUuid);
    // 승인이 성공했다면 최종 상태는 PAID여야 한다. 만료가 뒤늦게 덮어써 EXPIRED가 되면 불일치.
    if (approveOk && finalStatus !== 'PAID') tally.inconsistentFinalState++;
    if (approveOk && expireOk) tally.bothSucceeded++;
    else tally.oneSucceeded++;
  }
  return { tally, statusCodes };
}

async function scenarioParallelApprovals(sellerCookie) {
  const tally = { rounds: ROUNDS, parallelPerRound: PARALLEL, roundsWithExactlyOneSuccess: 0, roundsWithMultipleSuccess: 0, roundsWithZeroSuccess: 0, totalSuccess: 0, serverError500: 0 };
  const statusCodes = {};

  for (let i = 0; i < ROUNDS; i++) {
    const userCookie = await createUserWithCookie(`pa_${RUN_ID}_${i}`);
    const payUuid = await prepareDepositedPayment(userCookie, (i % 9) + 1);

    const results = await Promise.all(
      Array.from({ length: PARALLEL }, () =>
        request(`/api/payments/approve/${payUuid}`, { method: 'POST', cookie: sellerCookie }))
    );
    let success = 0;
    for (const r of results) {
      statusCodes[r.status] = (statusCodes[r.status] || 0) + 1;
      if (r.status === 200) success++;
      if (r.status >= 500) tally.serverError500++;
    }
    tally.totalSuccess += success;
    if (success === 1) tally.roundsWithExactlyOneSuccess++;
    else if (success > 1) tally.roundsWithMultipleSuccess++;
    else tally.roundsWithZeroSuccess++;
  }
  return { tally, statusCodes };
}

(async () => {
  const sellerCookie = await login('seller01');
  const a = await scenarioApproveVsCancel(sellerCookie);
  const c = await scenarioApproveVsExpire(sellerCookie);
  const b = await scenarioParallelApprovals(sellerCookie);
  console.log(JSON.stringify({ runId: RUN_ID, approveVsCancel: a, approveVsExpire: c, parallelApprovals: b }, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });

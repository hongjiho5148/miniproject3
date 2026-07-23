package mini_pjt3.com.team1.service.impl;

import mini_pjt3.com.team1.entity.Member;
import mini_pjt3.com.team1.entity.Payment;
import mini_pjt3.com.team1.entity.Product;
import mini_pjt3.com.team1.entity.VirtualAccount;
import mini_pjt3.com.team1.enums.AccountStatus;
import mini_pjt3.com.team1.enums.BankCode;
import mini_pjt3.com.team1.enums.Role;
import mini_pjt3.com.team1.enums.TransactionStatus;
import mini_pjt3.com.team1.repository.MemberRepository;
import mini_pjt3.com.team1.repository.PaymentHistoryRepository;
import mini_pjt3.com.team1.repository.PaymentRepository;
import mini_pjt3.com.team1.repository.ProductRepository;
import mini_pjt3.com.team1.repository.VirtualAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 결제 상태 전이(발급→입금대기→승인→만료) 규칙이 실제로 지켜지는지 검증하는 단위 테스트.
 * "입금 전 승인 시도", "이미 승인된 건 재승인 시도" 같은, 순서가 어긋난 요청이
 * 상태를 오염시키지 않고 예외로 막히는지를 확인하는 데 초점을 둔다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentHistoryRepository paymentHistoryRepository;
    @Mock private VirtualAccountRepository virtualAccountRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private static final Long SELLER_ID = 10L;

    private Product product;
    private Member buyer;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .name("맥북 프로")
                .price(2_800_000L)
                .sellerId(SELLER_ID)
                .build();

        buyer = Member.builder()
                .loginId("buyer01")
                .password("encoded")
                .name("구매자")
                .email("buyer@test.com")
                .role(Role.USER)
                .build();
    }

    private Payment paymentWithStatus(TransactionStatus status) {
        return Payment.builder()
                .totalAmount(2_800_000L)
                .productName(product.getName())
                .member(buyer)
                .product(product)
                .status(status)
                .build();
    }

    @Test
    void 입금_전에_승인을_시도하면_예외가_발생한다() {
        // given: 아직 입금 보고가 안 된(PENDING) 결제
        Payment payment = paymentWithStatus(TransactionStatus.PENDING);
        when(paymentRepository.findByPayUuid(payment.getPayUuid())).thenReturn(Optional.of(payment));

        // when & then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.approvePayment(payment.getPayUuid(), SELLER_ID));

        assertThat(ex.getMessage()).contains("입금 보고가 완료된 상태가 아닙니다");
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.PENDING); // 상태가 바뀌지 않았는지 확인
    }

    @Test
    void 이미_승인된_건을_재승인하려하면_예외가_발생한다() {
        // given: 이미 승인 완료(PAID)된 결제
        Payment payment = paymentWithStatus(TransactionStatus.PAID);
        when(paymentRepository.findByPayUuid(payment.getPayUuid())).thenReturn(Optional.of(payment));

        // when & then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.approvePayment(payment.getPayUuid(), SELLER_ID));

        assertThat(ex.getMessage()).contains("입금 보고가 완료된 상태가 아닙니다");
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.PAID); // 이미 PAID였던 상태 그대로 유지
    }

    @Test
    void 다른_판매자가_승인을_시도하면_예외가_발생한다() {
        // given: 입금까지는 정상적으로 완료된 결제
        Payment payment = paymentWithStatus(TransactionStatus.DEPOSITED);
        when(paymentRepository.findByPayUuid(payment.getPayUuid())).thenReturn(Optional.of(payment));

        Long anotherSellerId = 999L;

        // when & then: 상품을 등록한 판매자(10L)가 아닌 다른 판매자가 승인 시도
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.approvePayment(payment.getPayUuid(), anotherSellerId));

        assertThat(ex.getMessage()).contains("본인의 상품만 승인할 수 있습니다");
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.DEPOSITED);
    }

    @Test
    void 정상적인_승인_요청은_결제상태를_PAID로_바꾸고_이력을_남긴다() {
        // given: 입금 완료 상태 + 연결된 활성 가상계좌
        Payment payment = paymentWithStatus(TransactionStatus.DEPOSITED);
        VirtualAccount virtualAccount = VirtualAccount.builder()
                .accountNumber("032-123456789")
                .bankName("테스트은행")
                .bankCode(BankCode.KOOKMIN)
                .payment(payment)
                .build();

        when(paymentRepository.findByPayUuid(payment.getPayUuid())).thenReturn(Optional.of(payment));
        when(virtualAccountRepository.findByPaymentId(any())).thenReturn(Optional.of(virtualAccount));

        // when
        paymentService.approvePayment(payment.getPayUuid(), SELLER_ID);

        // then
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(virtualAccount.getStatus()).isEqualTo(AccountStatus.USED);
    }

    @Test
    void 입금대기_상태가_아닌_결제는_입금보고를_받을수_없다() {
        // given: 이미 입금 보고가 처리된(DEPOSITED) 결제
        Payment payment = paymentWithStatus(TransactionStatus.DEPOSITED);
        when(paymentRepository.findByPayUuid(payment.getPayUuid())).thenReturn(Optional.of(payment));

        // when & then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.reportDeposit(payment.getPayUuid()));

        assertThat(ex.getMessage()).contains("이미 처리 중이거나 완료된 결제");
    }

    @Test
    void 입금대기_상태의_결제는_정상적으로_입금보고를_받는다() {
        // given
        Payment payment = paymentWithStatus(TransactionStatus.PENDING);
        when(paymentRepository.findByPayUuid(payment.getPayUuid())).thenReturn(Optional.of(payment));

        // when
        paymentService.reportDeposit(payment.getPayUuid());

        // then
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.DEPOSITED);
    }
}

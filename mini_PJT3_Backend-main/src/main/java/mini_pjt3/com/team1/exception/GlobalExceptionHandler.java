package mini_pjt3.com.team1.exception;

import lombok.extern.slf4j.Slf4j;
import mini_pjt3.com.team1.dto.response.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러마다 제각각이던 예외 처리를 한곳에서 잡아, 상태 코드와 응답 형식을 통일한다.
 * (기존에는 서비스 계층 예외가 그대로 500으로 새는 경우가 있었음)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 존재하지 않는 리소스 조회 등 - 잘못된 요청
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }

    // 결제 상태 전이 위반, 소유권 불일치 등 - 현재 상태와 요청이 충돌
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("상태 충돌: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage()));
    }

    // 동시에 들어온 요청 중 하나가 낙관적 락 충돌로 실패한 경우
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "다른 요청이 먼저 처리되어 최신 상태와 충돌했습니다. 다시 시도해주세요."));
    }

    // 계좌 소유자가 아닌 경우 등 권한 없는 접근
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        log.warn("접근 권한 없음: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "해당 요청에 대한 접근 권한이 없습니다."));
    }

    // 그 외 예기치 못한 예외 - 상세 내용은 로그로만 남기고 클라이언트에는 일반 메시지만 반환
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("예기치 못한 서버 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "서버 처리 중 오류가 발생했습니다."));
    }
}

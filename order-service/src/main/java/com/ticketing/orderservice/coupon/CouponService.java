package com.ticketing.orderservice.coupon;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.coupon.dto.AvailableCouponResponse;
import com.ticketing.orderservice.coupon.dto.CouponCreateRequest;
import com.ticketing.orderservice.coupon.dto.CouponHistoryResponse;
import com.ticketing.orderservice.coupon.dto.CouponResponse;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// noRollbackFor ApiException. 이유는 PointService 참고(pay()가 자기 트랜잭션 안에서 이 예외들을 복구한다).
@Service
@Transactional(noRollbackFor = ApiException.class)
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;

    public CouponService(CouponRepository couponRepository, CouponRedemptionRepository redemptionRepository) {
        this.couponRepository = couponRepository;
        this.redemptionRepository = redemptionRepository;
    }

    public CouponResponse create(CouponCreateRequest request) {
        if (request.discountType() == DiscountType.PERCENT && request.discountValue() > 100) {
            throw new ApiException(ErrorCode.INVALID_INPUT);
        }
        if (!request.validFrom().isBefore(request.validTo())) {
            throw new ApiException(ErrorCode.INVALID_INPUT);
        }
        String code = normalize(request.code());
        if (couponRepository.existsByCode(code)) {
            throw new ApiException(ErrorCode.COUPON_CODE_EXISTS);
        }
        return CouponResponse.from(couponRepository.save(new Coupon(
                code,
                request.discountType(),
                request.discountValue(),
                request.validFrom(),
                request.validTo(),
                request.maxUses())));
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> listAll() {
        return couponRepository.findAll().stream().map(CouponResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CouponHistoryResponse> history(Long userId) {
        return redemptionRepository.findTop50ByUserIdOrderByIdDesc(userId).stream()
                .map(CouponHistoryResponse::from)
                .toList();
    }

    /** 이 사용자가 아직 쓸 수 있는 쿠폰이다. 활성 쿠폰은 모두 공개라서 특정인에게만 발급된 쿠폰은 없다. */
    @Transactional(readOnly = true)
    public List<AvailableCouponResponse> available(Long userId) {
        return couponRepository.findAvailableFor(userId, LocalDateTime.now()).stream()
                .map(AvailableCouponResponse::from)
                .toList();
    }

    /** 이 사용자가 지금 코드를 적용할 수 있는지 확인한다. 주문이 결제되기 전까지는 아무것도 소모하지 않는다(redeem 참고). */
    @Transactional(readOnly = true)
    public Coupon findUsable(String rawCode, Long userId) {
        Coupon coupon = couponRepository.findByCode(normalize(rawCode))
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND));
        if (!coupon.isUsableAt(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.COUPON_NOT_USABLE);
        }
        if (redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId)) {
            throw new ApiException(ErrorCode.COUPON_ALREADY_USED);
        }
        return coupon;
    }

    /** 주문 쿠폰의 한 번 사용을 실제로 소모한다. 카드 결제가 검증된 뒤에만 호출한다. */
    public void redeem(Orders order) {
        Coupon coupon = couponRepository.findByCode(order.getCouponCode())
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND));
        // 적용할 때만이 아니라 여기서도 다시 확인한다. 기간, 한도, 1인 1회 규칙이 모두 "적용"에서
        // "결제" 사이의 몇 분 동안 바뀌었을 수 있다.
        if (redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), order.getUserId())) {
            throw new ApiException(ErrorCode.COUPON_ALREADY_USED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidTo()) || couponRepository.markUsed(coupon.getId()) == 0) {
            throw new ApiException(ErrorCode.COUPON_NOT_USABLE);
        }
        redemptionRepository.save(new CouponRedemption(
                coupon.getId(), coupon.getCode(), order.getUserId(), order.getId(), order.getDiscountAmount()));
    }

    /**
     * 전액 환불된 주문의 redeem()을 되돌린다. 사용자는 쿠폰을 다시 쓸 수 있고 사용 횟수는 쿠폰의
     * 한도로 돌아간다. 멱등하다. 이 주문의 사용 기록 행이 실제로 지워질 때만 횟수를 돌려주므로,
     * 두 번 호출해도 다른 사람의 사용 횟수를 풀어 주는 일은 없다.
     */
    public void restore(Orders order) {
        if (order.getCouponCode() == null) {
            return;
        }
        couponRepository.findByCode(order.getCouponCode()).ifPresent(coupon -> {
            if (redemptionRepository.deleteByCouponIdAndUserIdAndOrderId(
                            coupon.getId(), order.getUserId(), order.getId()) > 0) {
                couponRepository.markUnused(coupon.getId());
            }
        });
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}

package com.ticketing.orderservice.coupon;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.coupon.dto.CouponCreateRequest;
import com.ticketing.orderservice.coupon.dto.CouponHistoryResponse;
import com.ticketing.orderservice.coupon.dto.CouponResponse;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// noRollbackFor ApiException - see PointService for why (pay() recovers from these inside its own transaction).
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

    /** Checks a code can be applied by this user right now. Nothing is consumed until the order is paid (see redeem). */
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

    /** Consumes one use of the order's coupon for real - called only once the card payment has been verified. */
    public void redeem(Orders order) {
        Coupon coupon = couponRepository.findByCode(order.getCouponCode())
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND));
        // Re-checked here, not just at apply time: the window/limit/one-per-user rules can all have
        // changed in the minutes between "apply" and "paid".
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

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}

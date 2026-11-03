package com.ticketing.orderservice.internal;

import com.ticketing.orderservice.internal.dto.OrderExistsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 사이 호출 전용이다. 공개 게이트웨이를 거치지 않고 docker 네트워크 안에서 직접 닿는다. */
@RestController
@RequestMapping("/internal/orders")
public class OrderInternalController {

    private final OrderInternalService orderInternalService;

    public OrderInternalController(OrderInternalService orderInternalService) {
        this.orderInternalService = orderInternalService;
    }

    @GetMapping("/exists")
    public OrderExistsResponse exists(@RequestParam Long userId, @RequestParam Long eventId) {
        return new OrderExistsResponse(orderInternalService.hasPaidOrder(userId, eventId));
    }
}

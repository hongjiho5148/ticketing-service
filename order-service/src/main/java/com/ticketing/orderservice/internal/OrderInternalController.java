package com.ticketing.orderservice.internal;

import com.ticketing.orderservice.internal.dto.OrderExistsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only - not routed through the public Gateway, reached directly within the docker network. */
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

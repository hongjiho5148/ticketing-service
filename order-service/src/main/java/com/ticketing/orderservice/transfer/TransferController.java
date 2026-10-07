package com.ticketing.orderservice.transfer;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.transfer.dto.TransferRequest;
import com.ticketing.orderservice.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Under /api/orders so the gateway's existing order-service route already covers it.
@RestController
@RequestMapping("/api/orders")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/{orderId}/transfer")
    public ResponseEntity<TransferResponse> create(@PathVariable Long orderId, @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transferService.create(SecurityUtil.getCurrentUserId(), orderId, request.email()));
    }

    @GetMapping("/transfers")
    public List<TransferResponse> list() {
        return transferService.list(SecurityUtil.getCurrentUserId());
    }

    @PostMapping("/transfers/{transferId}/accept")
    public ResponseEntity<Void> accept(@PathVariable Long transferId) {
        transferService.accept(SecurityUtil.getCurrentUserId(), transferId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transfers/{transferId}/decline")
    public ResponseEntity<Void> decline(@PathVariable Long transferId) {
        transferService.decline(SecurityUtil.getCurrentUserId(), transferId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transfers/{transferId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long transferId) {
        transferService.cancel(SecurityUtil.getCurrentUserId(), transferId);
        return ResponseEntity.noContent().build();
    }
}

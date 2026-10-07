package com.ticketing.eventservice.alert;

import com.ticketing.eventservice.alert.dto.AlertStatusResponse;
import com.ticketing.eventservice.auth.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-user alert subscriptions on an event. SecurityConfig requires a login for these despite the public GET /api/events/**. */
@RestController
@RequestMapping("/api/events/{eventId}")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/open-alert")
    public AlertStatusResponse openAlertStatus(@PathVariable Long eventId) {
        return alertService.openAlertStatus(SecurityUtil.getCurrentUserId(), eventId);
    }

    @PostMapping("/open-alert")
    public ResponseEntity<AlertStatusResponse> subscribeOpenAlert(@PathVariable Long eventId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alertService.subscribeOpenAlert(SecurityUtil.getCurrentUserId(), eventId));
    }

    @DeleteMapping("/open-alert")
    public ResponseEntity<Void> unsubscribeOpenAlert(@PathVariable Long eventId) {
        alertService.unsubscribeOpenAlert(SecurityUtil.getCurrentUserId(), eventId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/waitlist")
    public AlertStatusResponse waitlistStatus(@PathVariable Long eventId) {
        return alertService.waitlistStatus(SecurityUtil.getCurrentUserId(), eventId);
    }

    @PostMapping("/waitlist")
    public ResponseEntity<AlertStatusResponse> subscribeWaitlist(@PathVariable Long eventId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alertService.subscribeWaitlist(SecurityUtil.getCurrentUserId(), eventId));
    }

    @DeleteMapping("/waitlist")
    public ResponseEntity<Void> unsubscribeWaitlist(@PathVariable Long eventId) {
        alertService.unsubscribeWaitlist(SecurityUtil.getCurrentUserId(), eventId);
        return ResponseEntity.noContent().build();
    }
}

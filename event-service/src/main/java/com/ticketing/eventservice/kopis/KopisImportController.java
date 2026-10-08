package com.ticketing.eventservice.kopis;

import com.ticketing.eventservice.kopis.dto.KopisImportRequest;
import com.ticketing.eventservice.kopis.dto.KopisImportResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gated to ROLE_ADMIN by the /api/events/admin/** matcher in SecurityConfig. */
@RestController
@RequestMapping("/api/events/admin/import")
public class KopisImportController {

    private final KopisImportService importService;

    public KopisImportController(KopisImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/kopis")
    public KopisImportResponse importFromKopis(@Valid @RequestBody KopisImportRequest request) {
        return importService.importPerformances(request);
    }
}

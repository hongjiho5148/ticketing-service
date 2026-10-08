package com.ticketing.eventservice.kopis.dto;

import java.util.List;

public record KopisImportResponse(
        int created, int alreadyImported, int notUpcoming, int failed, List<String> createdTitles) {
}

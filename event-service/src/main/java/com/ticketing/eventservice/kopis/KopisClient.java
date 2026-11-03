package com.ticketing.eventservice.kopis;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Thin client for the KOPIS open API (공연예술통합전산망). The service key is the operator's own, from kopis.or.kr. */
@Component
public class KopisClient {

    private static final DateTimeFormatter PARAM_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    // KOPIS는 초당 10회를 넘는 키를 정지시킨다. 초당 약 5회를 유지하면 여유가 넉넉하다.
    private static final long MIN_INTERVAL_MS = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String baseUrl;
    private final String serviceKey;

    public KopisClient(
            @Value("${kopis.base-url}") String baseUrl, @Value("${kopis.service-key:}") String serviceKey) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    public List<KopisPerformance> fetchList(KopisGenre genre, LocalDate from, LocalDate to, int page, int rows) {
        String query = "service=" + encode(serviceKey)
                + "&stdate=" + from.format(PARAM_DATE)
                + "&eddate=" + to.format(PARAM_DATE)
                + "&cpage=" + page
                + "&rows=" + rows
                + "&shcate=" + genre.code();
        return KopisXmlParser.parse(get("/pblprfr?" + query));
    }

    /** 가격, 출연진, 줄거리를 담고 있는 호출은 상세 호출뿐이다. */
    public KopisPerformance fetchDetail(String id) {
        List<KopisPerformance> parsed =
                KopisXmlParser.parse(get("/pblprfr/" + encode(id) + "?service=" + encode(serviceKey)));
        if (parsed.isEmpty()) {
            throw new KopisException("공연 상세를 찾지 못했어요: " + id);
        }
        return parsed.get(0);
    }

    private long lastCallAt;

    private synchronized void throttle() {
        long wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KopisException("KOPIS 호출이 중단됐어요.");
            }
        }
        lastCallAt = System.currentTimeMillis();
    }

    private String get(String pathAndQuery) {
        throttle();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new KopisException("KOPIS 응답 코드 " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            // 일부러 이어 붙이지 않았다. 요청 URL에 서비스 키가 들어 있어서 로그에 남으면 안 된다.
            throw new KopisException("KOPIS에 연결하지 못했어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KopisException("KOPIS 호출이 중단됐어요.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

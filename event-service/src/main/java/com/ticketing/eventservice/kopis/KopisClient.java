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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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

    /** The detail call is the only one that carries prices, cast and the synopsis. */
    public KopisPerformance fetchDetail(String id) {
        List<KopisPerformance> parsed =
                KopisXmlParser.parse(get("/pblprfr/" + encode(id) + "?service=" + encode(serviceKey)));
        if (parsed.isEmpty()) {
            throw new KopisException("공연 상세를 찾지 못했어요: " + id);
        }
        return parsed.get(0);
    }

    private String get(String pathAndQuery) {
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
            // Deliberately not chained: the request URL carries the service key and must not reach the logs.
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

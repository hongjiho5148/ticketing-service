package com.ticketing.eventservice.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.kopis.KopisImportService.Plan;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KopisImportTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 14, 30);

    private static final String DETAIL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dbs>
              <db>
                <mt20id>PF123456</mt20id>
                <prfnm>세 자매</prfnm>
                <prfpdfrom>2026.10.01</prfpdfrom>
                <prfpdto>2026.11.30</prfpdto>
                <fcltynm>대학로 예술극장 (대극장)</fcltynm>
                <prfcast>김하늘, 이도윤</prfcast>
                <prfruntime>2시간 10분</prfruntime>
                <prfage>만 12세 이상</prfage>
                <pcseguidance>VIP석 80,000원, R석 60,000원, S석 40,000원</pcseguidance>
                <genrenm>연극</genrenm>
                <dtguidance>화요일 ~ 금요일(20:00), 토요일(15:00,19:00)</dtguidance>
                <sty>세 자매의 이야기.</sty>
              </db>
            </dbs>
            """;

    @Test
    void parsesPerformanceXml() {
        List<KopisPerformance> parsed = KopisXmlParser.parse(DETAIL_XML);

        assertThat(parsed).hasSize(1);
        KopisPerformance p = parsed.get(0);
        assertThat(p.id()).isEqualTo("PF123456");
        assertThat(p.title()).isEqualTo("세 자매");
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(p.venue()).isEqualTo("대학로 예술극장 (대극장)");
        assertThat(p.priceGuidance()).contains("VIP석 80,000원");
    }

    @Test
    void blankFieldsBecomeNull() {
        KopisPerformance p = KopisXmlParser.parse(
                "<dbs><db><mt20id>PF1</mt20id><prfnm>제목</prfnm><prfcast> </prfcast></db></dbs>").get(0);

        assertThat(p.cast()).isNull();
        assertThat(p.from()).isNull();
    }

    @Test
    void apiErrorInsideA200ResponseSurfaces() {
        String error = "<dbs><db><returncode>01</returncode><errmsg>SERVICE KEY IS NOT REGISTERED</errmsg></db></dbs>";

        assertThatThrownBy(() -> KopisXmlParser.parse(error))
                .isInstanceOf(KopisException.class)
                .hasMessageContaining("SERVICE KEY IS NOT REGISTERED");
    }

    @Test
    void refusesDoctypeDeclarations() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE dbs [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <dbs><db><mt20id>&x;</mt20id><prfnm>t</prfnm></db></dbs>
                """;

        assertThatThrownBy(() -> KopisXmlParser.parse(xxe)).isInstanceOf(KopisException.class);
    }

    @Test
    void extractsDistinctPricesHighestFirst() {
        assertThat(KopisPricing.parsePrices("VIP석 150,000원, R석 130,000원, S석 100,000원, 청소년 100,000원"))
                .containsExactly(150_000, 130_000, 100_000);
        assertThat(KopisPricing.parsePrices("전석 30000원")).containsExactly(30_000);
        assertThat(KopisPricing.parsePrices("전석 무료")).isEmpty();
        assertThat(KopisPricing.parsePrices(null)).isEmpty();
    }

    @Test
    void foldsAnyNumberOfPriceTiersIntoVipRS() {
        assertThat(KopisPricing.toGrades(List.of(50_000))).extracting("grade").containsExactly("R");
        assertThat(KopisPricing.toGrades(List.of(80_000, 40_000))).extracting("grade").containsExactly("R", "S");
        assertThat(KopisPricing.toGrades(List.of(150_000, 130_000, 110_000, 90_000, 70_000)))
                .extracting("price")
                .containsExactly(150_000, 110_000, 70_000);
    }

    @Test
    void midRunShowIsListedFromTomorrowAtItsFirstShowTime() {
        Plan plan = KopisImportService.plan(KopisXmlParser.parse(DETAIL_XML).get(0), NOW).orElseThrow();

        assertThat(plan.event().getStartAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 20, 0));
        assertThat(plan.event().getOpenAt()).isBefore(plan.event().getStartAt());
        assertThat(plan.event().getCategory()).isEqualTo(EventCategory.PLAY);
        assertThat(plan.event().getSourceUrl()).endsWith("mt20Id=PF123456");
        assertThat(plan.event().getDescription()).contains("세 자매의 이야기.").contains("공연예술통합전산망(KOPIS)");
        assertThat(plan.grades()).extracting("grade").containsExactly("VIP", "R", "S");
    }

    @Test
    void futureShowStartsOnItsFirstDay() {
        KopisPerformance p = new KopisPerformance("PF9", "개막작", LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 31),
                "홀", "뮤지컬", null, null, null, "전석 55,000원", null, null);

        Plan plan = KopisImportService.plan(p, NOW).orElseThrow();

        assertThat(plan.event().getStartAt()).isEqualTo(LocalDateTime.of(2026, 12, 20, 19, 0));
        assertThat(plan.event().getCategory()).isEqualTo(EventCategory.MUSICAL);
    }

    @Test
    void finishedShowIsSkipped() {
        KopisPerformance p = new KopisPerformance("PF8", "끝난 공연", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 8),
                "홀", "연극", null, null, null, null, null, null);

        assertThat(KopisImportService.plan(p, NOW)).isEqualTo(Optional.empty());
    }

    @Test
    void showWithoutPriceInfoGetsClearlyLabelledDemoPrices() {
        KopisPerformance p = new KopisPerformance("PF7", "무료 공연", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2),
                "홀", "서양음악(클래식)", null, null, null, "전석 무료", null, null);

        Plan plan = KopisImportService.plan(p, NOW).orElseThrow();

        assertThat(plan.grades()).extracting("grade").containsExactly("R", "S");
        assertThat(plan.event().getDescription()).contains("데모 가격");
        assertThat(plan.event().getCategory()).isEqualTo(EventCategory.CLASSIC);
    }

    @Test
    void mapsKopisGenreNamesToOurCategories() {
        assertThat(KopisImportService.categoryOf("연극")).isEqualTo(EventCategory.PLAY);
        assertThat(KopisImportService.categoryOf("대중음악")).isEqualTo(EventCategory.CONCERT);
        assertThat(KopisImportService.categoryOf("한국음악(국악)")).isEqualTo(EventCategory.ETC);
        assertThat(KopisImportService.categoryOf(null)).isEqualTo(EventCategory.ETC);
    }

    @Test
    void readsFirstShowTimeOrDefaultsToSevenPm() {
        assertThat(KopisImportService.showTime("화~금(20:00), 토(15:00,19:00)")).isEqualTo(LocalTime.of(20, 0));
        assertThat(KopisImportService.showTime("공연 시간은 추후 공지")).isEqualTo(LocalTime.of(19, 0));
        assertThat(KopisImportService.showTime(null)).isEqualTo(LocalTime.of(19, 0));
    }
}

package com.ticketing.eventservice.kopis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOPIS는 가격을 자유 형식 문자열로 준다("VIP석 150,000원, R석 130,000원, S석 100,000원" 또는 "전석 30,000원").
 * 여기서는 금액만 뽑는다. 그것으로 만든 좌석 배치는 우리가 직접 만든 것이다. KOPIS에는 좌석 데이터가 없기 때문이다.
 */
final class KopisPricing {

    private static final Pattern WON = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{4,})\\s*원");
    private static final int MIN_PRICE = 1_000;

    private KopisPricing() {
    }

    /** Distinct prices, highest first. Empty for "전석 무료", blank or unparseable text. */
    static List<Integer> parsePrices(String guidance) {
        if (guidance == null) {
            return List.of();
        }
        List<Integer> prices = new ArrayList<>();
        Matcher matcher = WON.matcher(guidance);
        while (matcher.find()) {
            int price = Integer.parseInt(matcher.group(1).replace(",", ""));
            if (price >= MIN_PRICE && !prices.contains(price)) {
                prices.add(price);
            }
        }
        prices.sort(Comparator.reverseOrder());
        return prices;
    }

    /** KOPIS 가격대를 몇 개든 앱이 아는 세 등급(VIP, R, S) 이하로 합친다. */
    static List<GradePrice> toGrades(List<Integer> pricesDescending) {
        int n = pricesDescending.size();
        if (n == 0) {
            return List.of();
        }
        if (n == 1) {
            return List.of(new GradePrice("R", pricesDescending.get(0)));
        }
        if (n == 2) {
            return List.of(new GradePrice("R", pricesDescending.get(0)), new GradePrice("S", pricesDescending.get(1)));
        }
        return List.of(
                new GradePrice("VIP", pricesDescending.get(0)),
                new GradePrice("R", pricesDescending.get(n / 2)),
                new GradePrice("S", pricesDescending.get(n - 1)));
    }

    record GradePrice(String grade, int price) {
    }
}

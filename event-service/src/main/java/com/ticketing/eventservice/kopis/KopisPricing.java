package com.ticketing.eventservice.kopis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOPIS describes prices as free text ("VIP석 150,000원, R석 130,000원, S석 100,000원" or "전석 30,000원").
 * This only extracts the amounts; the seat map built from them is our own, since KOPIS has no seating data.
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

    /** Collapses any number of KOPIS price points into at most the three grades the app knows: VIP, R, S. */
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

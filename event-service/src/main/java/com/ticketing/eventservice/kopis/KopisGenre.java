package com.ticketing.eventservice.kopis;

/** 가져오기로 제공하는 KOPIS 장르 코드({@code shcate} 필터)다. */
public enum KopisGenre {
    PLAY("AAAA"),
    MUSICAL("GGGA"),
    CLASSIC("CCCA"),
    KOREAN_MUSIC("CCCC"),
    POPULAR_MUSIC("CCCD"),
    DANCE("BBBC");

    private final String code;

    KopisGenre(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

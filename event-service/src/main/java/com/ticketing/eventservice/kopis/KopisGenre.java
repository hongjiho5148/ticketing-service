package com.ticketing.eventservice.kopis;

/** The KOPIS genre codes (the {@code shcate} filter) we offer for import. */
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

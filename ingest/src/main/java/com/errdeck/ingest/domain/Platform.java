package com.errdeck.ingest.domain;

public enum Platform {
    SPRING,
    VUE;

    public static Platform fromDb(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}

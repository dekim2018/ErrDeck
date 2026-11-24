package com.errdeck.api.domain;

import java.util.Locale;

/** 프로젝트의 종류. 브라우저 프로젝트(vue)는 출처를 확인한다(15.5). */
public enum Platform {
    SPRING, VUE;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Platform fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}

package com.errdeck.loadgen;

/** 18.2 의 부하 실험 중 이 도구가 만드는 것. */
public enum Scenario {

    /** 평소 부하. 지문 여러 종류의 에러를 고르게 섞는다. */
    SUSTAINED,
    /** 감시 대상의 장애. 같은 에러가 한꺼번에 쏟아진다(5.1). */
    BURST,
    /** 봉투가 아니라 임의 주소에 GET 을 보낸다. 데모 앱으로 SDK 를 켠 것과 끈 것의 요청 시간을 비교한다(18.2). */
    HTTP;

    static Scenario parse(String name) {
        return switch (name) {
            case "sustained" -> SUSTAINED;
            case "burst" -> BURST;
            case "http" -> HTTP;
            default -> throw new IllegalArgumentException("시나리오는 sustained, burst, http 중 하나다: " + name);
        };
    }
}

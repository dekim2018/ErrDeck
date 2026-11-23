package com.errdeck.core.envelope;

/** 봉투에 담기는 항목의 종류(5.3). */
public enum ItemType {

    /** 에러 1건. */
    EVENT("event"),
    /** 요청 1건의 트랜잭션과 그 안의 스팬. */
    TRANSACTION("transaction"),
    /** SDK가 버린 건수. */
    CLIENT_REPORT("client_report");

    private final String wireName;

    ItemType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ItemType fromWireName(String name) {
        for (ItemType type : values()) {
            if (type.wireName.equals(name)) {
                return type;
            }
        }
        throw new EnvelopeException("알 수 없는 항목 종류: " + name);
    }
}

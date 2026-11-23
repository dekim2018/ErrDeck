package com.errdeck.api.out.clickhouse;

public class ClickHouseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClickHouseException(String message) {
        super(message);
    }

    public ClickHouseException(String message, Throwable cause) {
        super(message, cause);
    }
}

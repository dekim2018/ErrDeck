package com.errdeck.core.envelope;

/** 봉투가 크기 상한을 넘었다. ingest는 413으로, worker는 격리로 처리한다. */
public class EnvelopeTooLargeException extends EnvelopeException {

    private static final long serialVersionUID = 1L;

    public EnvelopeTooLargeException(String message) {
        super(message);
    }
}

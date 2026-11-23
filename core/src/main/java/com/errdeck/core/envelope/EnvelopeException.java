package com.errdeck.core.envelope;

/** 봉투를 받아들일 수 없을 때 던진다. 형식이 틀렸거나, 잘렸거나, 상한을 넘었을 때다. */
public class EnvelopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EnvelopeException(String message) {
        super(message);
    }

    public EnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}

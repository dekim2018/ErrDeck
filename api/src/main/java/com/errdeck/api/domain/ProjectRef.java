package com.errdeck.api.domain;

/** 권한을 가릴 때 필요한 프로젝트의 위치. */
public record ProjectRef(long projectId, long systemId) {
}

package com.errdeck.core.sourcemap;

/**
 * 원본 위치.
 *
 * @param source 소스맵의 sources 에 적힌 경로 그대로(sourceRoot 가 있으면 앞에 붙임)
 * @param line   1부터 센 줄
 * @param column 0부터 센 열
 * @param name   원본의 이름. 없으면 null
 */
public record OriginalPosition(String source, int sourceIndex, int line, int column, String name) {
}

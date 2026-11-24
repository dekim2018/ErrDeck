# syntax=docker/dockerfile:1
# worker 이미지. 저장소 루트에서 빌드한다(compose 의 build.context). JVM 옵션은 compose 의 JAVA_OPTS 로 준다(14.2, 14.8).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# 세 이미지를 함께 빌드하면 Gradle 캐시를 동시에 쓰다 실패하므로 차례로 쓰게 잠근다.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon -q :worker:bootJar \
    && find worker/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 errdeck && mkdir -p /data/artifacts && chown errdeck /data/artifacts
WORKDIR /app
COPY --from=build /app.jar app.jar
USER errdeck
EXPOSE 8082 8091
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

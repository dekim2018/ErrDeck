# 먼저 ./gradlew :loadgen:installDist 로 실행 파일을 만든다. 저장소 루트에서 빌드한다(compose 의 build.context).
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY loadgen/build/install/loadgen/ ./
ENTRYPOINT ["/app/bin/loadgen"]

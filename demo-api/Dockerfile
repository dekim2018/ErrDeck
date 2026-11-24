# 먼저 ./gradlew :demo-api:bootJar 로 jar 를 만든다. 저장소 루트에서 빌드한다(compose 의 build.context).
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY demo-api/build/libs/demo-api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

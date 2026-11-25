# ---------- build ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hrms && adduser -S hrms -G hrms \
    && mkdir -p /data/files && chown -R hrms:hrms /data \
    && apk add --no-cache wget tzdata
ENV TZ=Asia/Colombo \
    FILE_STORAGE_PATH=/data/files
WORKDIR /app
COPY --from=build /src/target/ciaboc-hrms-*.jar /app/app.jar
USER hrms
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]

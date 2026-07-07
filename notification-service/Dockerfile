FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY banking-events/pom.xml banking-events/
COPY banking-common/pom.xml banking-common/
COPY backend/pom.xml backend/
COPY auth-service/pom.xml auth-service/
COPY api-gateway/pom.xml api-gateway/
COPY notification-service/pom.xml notification-service/

RUN mvn dependency:go-offline -pl banking-events,banking-common,notification-service -am -q

COPY banking-events/src banking-events/src
COPY banking-common/src banking-common/src
COPY notification-service/src notification-service/src

RUN mvn package -pl banking-events,banking-common,notification-service -am -DskipTests -q

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/notification-service/target/notification-service-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]

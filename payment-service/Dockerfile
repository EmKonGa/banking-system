FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY banking-events/pom.xml banking-events/
COPY banking-common/pom.xml banking-common/
COPY backend/pom.xml backend/
COPY auth-service/pom.xml auth-service/
COPY api-gateway/pom.xml api-gateway/
COPY notification-service/pom.xml notification-service/
COPY payment-service/pom.xml payment-service/

RUN mvn dependency:go-offline -pl banking-events,banking-common,payment-service -am -q

COPY banking-events/src banking-events/src
COPY banking-common/src banking-common/src
COPY payment-service/src payment-service/src

RUN mvn package -pl banking-events,banking-common,payment-service -am -DskipTests -q

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/payment-service/target/payment-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

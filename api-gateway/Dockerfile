FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY banking-events/pom.xml banking-events/
COPY banking-common/pom.xml banking-common/
COPY backend/pom.xml backend/
COPY auth-service/pom.xml auth-service/
COPY api-gateway/pom.xml api-gateway/

RUN mvn dependency:go-offline -pl api-gateway -am -q

COPY api-gateway/src api-gateway/src

RUN mvn package -pl api-gateway -am -DskipTests -q

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

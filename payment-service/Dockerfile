FROM maven:3.9-eclipse-temurin-21 AS shared
ENV MAVEN_OPTS="-Daether.connector.https.securityMode=insecure -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true"
WORKDIR /workspace
COPY pom.xml .
COPY banking-events/pom.xml banking-events/
COPY banking-common/pom.xml banking-common/
COPY auth-service/pom.xml auth-service/
COPY api-gateway/pom.xml api-gateway/
COPY account-service/pom.xml account-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
COPY banking-events/src banking-events/src
COPY banking-common/src banking-common/src
RUN mvn install -pl banking-events,banking-common -DskipTests -q

FROM shared AS build
COPY payment-service/src payment-service/src
RUN mvn package -pl banking-events,banking-common,payment-service -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/payment-service/target/payment-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

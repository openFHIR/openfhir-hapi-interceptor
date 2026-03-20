FROM maven:3.8.3-openjdk-17 AS builder

WORKDIR /app
COPY ./pom.xml /app/pom.xml
RUN mvn dependency:go-offline

COPY ./src /app/src
RUN mvn clean package -DskipTests

FROM hapiproject/hapi:v8.2.0-2

COPY --from=builder /app/target/*.jar /app/extra-classes/
ENV HAPI_FHIR_IPS_ENABLED=true
EXPOSE 8080 5005

# syntax=docker/dockerfile:1
# One Dockerfile for both Java services. Pick the service with --build-arg MODULE=controller|switch-network.
# The code is compiled inside Docker, so the host machine needs no Java or Maven.

# ---------- build stage ----------
FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY switch-network/pom.xml switch-network/
COPY controller/pom.xml controller/
COPY common/src common/src
COPY switch-network/src switch-network/src
COPY controller/src controller/src
# Maven's download cache is kept between builds, so rebuilds are fast.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am package -DskipTests

# ---------- run stage ----------
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

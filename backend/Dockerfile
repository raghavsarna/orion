# syntax=docker/dockerfile:1
# One Dockerfile for both Java services. Pick one with --build-arg MODULE=sdn-controller|switch-network.
# The code is compiled inside Docker, so the host machine needs no Java or Maven.

# ---------- build stage ----------
FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY shared/pom.xml shared/
COPY switch-network/pom.xml switch-network/
COPY sdn-controller/pom.xml sdn-controller/
COPY system-test/pom.xml system-test/
COPY shared/src shared/src
COPY ${MODULE}/src ${MODULE}/src
# Maven's download cache is kept between builds, so rebuilds are fast.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am package -DskipTests

# ---------- run stage ----------
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

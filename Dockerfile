FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY engine/pom.xml engine/pom.xml
COPY app/pom.xml app/pom.xml
COPY engine/src engine/src
COPY app/src app/src
COPY contracts contracts
RUN chmod +x mvnw && ./mvnw -B -q -pl app -am -Pproduction -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 sentry && mkdir -p /home/sentry/data && chown sentry /home/sentry/data
WORKDIR /home/sentry
COPY --from=build /src/app/target/sentry-app-0.1.0.jar app.jar
USER sentry
ENV SENTRY_DATA_DIR=/home/sentry/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--server.address=0.0.0.0"]

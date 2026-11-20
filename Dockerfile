# Two stages: build the jar with the full JDK, then run it on a smaller JRE-only image.

# ---- build ----
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace

# Download dependencies first, in their own layer, so code changes don't re-download them.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/depguard-*.jar app.jar

# ---- run ----
FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app

# Never run as root inside the container.
RUN useradd --system --uid 10001 depguard
USER depguard

COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

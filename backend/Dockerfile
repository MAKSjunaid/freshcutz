# ---- Build stage -----------------------------------------------------
# Compiles the app with Maven + Java 21. Render (and most hosts) don't run
# a JVM natively, so we build and run inside Docker instead.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the POM first and download dependencies separately so Docker can
# cache this layer — later builds only re-run this step if pom.xml changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Run stage ---------------------------------------------------------
# A much smaller image that only has the JRE (no Maven, no build tools).
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# fontconfig: Alpine's base JRE image ships without it, and some JVM
# internals touched by java.awt/ImageIO (used by ProductImageProcessor to
# resize and re-compress uploaded product photos) can crash looking for
# it even for code that never renders any text — a well-known Alpine-JRE
# gotcha, not specific to this app. Cheap to install up front rather than
# discover it the first time someone uploads a photo in production.
RUN apk add --no-cache fontconfig

COPY --from=build /app/target/freshcutz-backend.jar app.jar

# Render sets $PORT at runtime; application.properties already reads it
# (server.port=${PORT:8080}), so nothing else to configure here.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

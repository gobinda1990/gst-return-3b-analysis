# ============================================================
# Stage 1: Build
# ============================================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .

RUN mvn dependency:go-offline -B

COPY src ./src

RUN mvn clean package -DskipTests


# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:17-jre

# ------------------------------------------------------------
# Runtime dependencies
# ------------------------------------------------------------
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libgomp1 \
        tzdata \
    && rm -rf /var/lib/apt/lists/*

# ------------------------------------------------------------
# Timezone
# ------------------------------------------------------------
ENV TZ=Asia/Kolkata

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8087

# ------------------------------------------------------------
# Start Spring Boot
# ------------------------------------------------------------
ENTRYPOINT ["java", "-Duser.timezone=Asia/Kolkata", "-jar", "app.jar"]

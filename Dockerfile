# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src ./src
RUN ./mvnw -q -B package -DskipTests

# Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl ca-certificates java-cacerts openssl \
	&& update-ca-certificates \
	&& openssl s_client -showcerts -connect gigachat.devices.sberbank.ru:443 -servername gigachat.devices.sberbank.ru < /dev/null 2>/dev/null \
	| awk '/-----BEGIN CERTIFICATE-----/{n++} {print > ("/tmp/gigachat-cert-" n ".pem")} /-----END CERTIFICATE-----/{close("/tmp/gigachat-cert-" n ".pem")}' \
	&& for cert in /tmp/gigachat-cert-*.pem; do \
		if [ -s "$cert" ]; then \
			keytool -importcert -noprompt -trustcacerts -alias "gigachat-$(basename "$cert" .pem)" -file "$cert" -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit || true; \
		fi; \
	done \
	&& rm -f /tmp/gigachat-cert-*.pem \
	&& addgroup -S spring && adduser -S spring -G spring \
	&& mkdir -p /app/storage && chown -R spring:spring /app/storage
USER spring:spring
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

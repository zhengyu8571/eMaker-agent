FROM eclipse-temurin:17-jre
WORKDIR /app
COPY tj-aigc/target/tj-aigc.jar /app/agent.jar
EXPOSE 8094
ENTRYPOINT ["java", "-jar", "/app/agent.jar"]

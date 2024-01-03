FROM openjdk:17-jdk

ARG JAR_FILE_PATH=build/libs/*.jar

COPY $JAR_FILE_PATH app.jar

EXPOSE 8080
EXPOSE 1935

ENTRYPOINT ["java", "-Dspring.profiles.active=deploy", "-jar", "app.jar"]
@ECHO OFF
SET APP_HOME=%~dp0
IF NOT EXIST "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  ECHO gradle-wrapper.jar is not vendored in this source archive.
  ECHO Open in Android Studio or run: gradle wrapper --gradle-version 9.5.0
  EXIT /B 2
)
java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

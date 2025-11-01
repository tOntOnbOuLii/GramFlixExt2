#!/usr/bin/env sh

##############################################################################
# Gradle start up script for UN*X
##############################################################################

APP_BASE_NAME=${0##*/}
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVA_EXE="java"
exec "$JAVA_EXE" -Dorg.gradle.appname=$APP_BASE_NAME -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"


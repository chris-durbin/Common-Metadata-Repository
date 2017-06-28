#!/bin/sh
# This script is used to install any missing dependencies so TravisCI can run build scripts
# It is assumed that this is being run from the CMR root directory

cd oracle-lib/support

git clone "https://${CMR_ORACLE_JAR_USER}:${CMR_ORACLE_JAR_PASSWORD}@${CMR_ORACLE_JAR_REPO}"

cp cmr-deps/*.jar .

sh install_oracle_jars.sh

cd ../../

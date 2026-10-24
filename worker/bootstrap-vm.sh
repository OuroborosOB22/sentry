#!/bin/sh
# Run once on a fresh Ubuntu VM as the worker user. It installs what the worker needs and nothing else.
set -eu
sudo apt-get update -y
sudo apt-get install -y openjdk-21-jdk-headless unzip zip
mkdir -p "$HOME/sentry" "$HOME/sentry-jobs"
chmod 700 "$HOME/sentry" "$HOME/sentry-jobs"
echo "Copy sentry-engine-0.1.0-cli.jar and mvnw (with .mvn) into $HOME/sentry and put sentry-worker on the PATH."

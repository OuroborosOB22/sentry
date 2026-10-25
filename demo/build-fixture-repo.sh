#!/bin/sh
# builds a tiny local Maven repository with four versions of one library
set -e
here="$(cd "$(dirname "$0")" && pwd)"
repo="$here/fixture-repo"
work="$(mktemp -d)"
publish() {
  version="$1"; source="$2"
  folder="$repo/org/upgradeguard/fixture/demo-lib/$version"
  mkdir -p "$folder" "$work/$version/demo"
  printf '%s' "$source" > "$work/$version/demo/Greeter.java"
  javac --release 21 -d "$work/$version/classes" "$work/$version/demo/Greeter.java"
  jar cf "$folder/demo-lib-$version.jar" -C "$work/$version/classes" .
  printf '<project><modelVersion>4.0.0</modelVersion><groupId>org.upgradeguard.fixture</groupId><artifactId>demo-lib</artifactId><version>%s</version></project>\n' "$version" > "$folder/demo-lib-$version.pom"
}
publish 1.0.0 'package demo; public class Greeter { public String greet(String name) { return "Hello, " + name; } }'
publish 1.1.0 'package demo; public class Greeter { public String greet(String name) { return "Hello, " + name; } public String greetLoud(String name) { return greet(name).toUpperCase(); } }'
publish 1.2.0 'package demo; public class Greeter { public String greet(String name) { return "Hi " + name; } }'
publish 2.0.0 'package demo; public class Greeter { public String salute(String name) { return "Greetings, " + name; } }'
rm -rf "$work"
echo "fixture repository ready in $repo"

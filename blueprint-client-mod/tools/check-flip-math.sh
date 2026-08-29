#!/bin/sh
# Compiles and runs the auction flipper's maths on their own.
#
# The price parsing, the price model and the scoring have no Minecraft in them,
# so they can be checked without the game, the mappings, or a Gradle build.
set -e

here=$(dirname "$0")
root=$(cd "$here/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

javac -d "$out" \
	"$root/src/main/java/com/blueprintclient/flip/PriceText.java" \
	"$root/src/main/java/com/blueprintclient/flip/MarketModel.java" \
	"$root/src/main/java/com/blueprintclient/flip/FlipSettings.java" \
	"$root/src/main/java/com/blueprintclient/flip/FlipMath.java" \
	"$root/tools/FlipMathCheck.java"

java -cp "$out" FlipMathCheck

#!/bin/sh
set -eu
SDK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TEST_CLASSES=$(mktemp -d)
trap 'rm -rf "$TEST_CLASSES"' EXIT HUP INT TERM
javac -d "$TEST_CLASSES" \
    "$SDK_ROOT/lineage/lib/main/java/org/lineageos/platform/internal/health/ChargingControlOwner.java" \
    "$SDK_ROOT/tests/charging-owner/org/lineageos/platform/internal/health/ChargingControlOwnerTest.java"
java -cp "$TEST_CLASSES" org.lineageos.platform.internal.health.ChargingControlOwnerTest

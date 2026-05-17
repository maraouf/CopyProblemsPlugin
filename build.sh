#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
./gradlew buildPlugin
echo
echo "Built plugin zip(s):"
ls -1 dist/*.zip 2>/dev/null || ls -1 build/distributions/*.zip

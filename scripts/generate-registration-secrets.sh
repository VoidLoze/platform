#!/usr/bin/env sh
# Генерация пары секретов (32 байта энтропии каждый, Base64 URL-safe).
set -e
cd "$(dirname "$0")/.."
exec mvn -q compile exec:java -Dexec.mainClass=diplom.tools.GenerateRegistrationSecrets

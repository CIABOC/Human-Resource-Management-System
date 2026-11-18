#!/bin/sh
# Generates a self-signed certificate for local/UAT use only.
# Production must use a certificate issued for the approved CIABOC host name.
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$DIR"
if [ -f "$DIR/server.crt" ]; then
  echo "Certificate already exists in $DIR"
  exit 0
fi
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout "$DIR/server.key" -out "$DIR/server.crt" \
  -subj "/C=LK/O=CIABOC/OU=HRMS/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
chmod 600 "$DIR/server.key"
echo "Self-signed certificate written to $DIR"

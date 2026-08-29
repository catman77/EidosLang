#!/usr/bin/env bash
#
# Put an EidoLang relay on this machine.
#
#   sudo ./relay/install.sh                    # first install, self-signed certificate generated
#   sudo ./relay/install.sh --cert /path/dir   # reuse an existing cert.pem / key.pem
#
# The relay does two narrow jobs and holds no key that could open a message:
#   * a directory, so people can be found by nickname;
#   * a left-luggage office for encrypted conversation segments, so a message survives its author
#     closing the app.
# Delivery works without it — two phones reach each other directly over Tor — but only while both
# are awake at the same moment. That is what the relay removes.
set -euo pipefail

STATE_VOLUME=eidolang-relay-state
IMAGE=eidolang-relay
NAME=eidolang-relay
INSTALL_DIR=/opt/eidolang-relay
CERT_DIR="$INSTALL_DIR/tls"
SRC="$(cd "$(dirname "$0")" && pwd)"

while [ $# -gt 0 ]; do
  case "$1" in
    --cert) CERT_SRC="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

mkdir -p "$INSTALL_DIR" "$CERT_DIR"
cp "$SRC/relay.py" "$SRC/Dockerfile" "$INSTALL_DIR/"

if [ -n "${CERT_SRC:-}" ]; then
  cp "$CERT_SRC/cert.pem" "$CERT_SRC/key.pem" "$CERT_DIR/"
  echo "== using the certificate you supplied"
elif [ -f "$CERT_DIR/cert.pem" ] && [ -f "$CERT_DIR/key.pem" ]; then
  echo "== keeping the certificate already installed"
else
  # Self-signed on purpose. The app pins this certificate and trusts nothing else for the relay,
  # which is narrower than trusting a public CA for it and narrower than allowing cleartext.
  echo "== generating a self-signed certificate"
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$CERT_DIR/key.pem" -out "$CERT_DIR/cert.pem" \
    -subj "/CN=eidolang-relay" >/dev/null 2>&1
  cat <<'WARN'

  !! A NEW certificate was generated, and the published app does not trust it.
     The app pins the certificate, not the address, so a relay presenting an unknown one is
     refused - deliberately: accepting whatever is offered would be worse than the problem it
     solves. To use this relay you must also put the new certificate into the app and rebuild:

         cp /opt/eidolang-relay/tls/cert.pem android/app/src/main/res/raw/eidolang_relay.pem
         cp /opt/eidolang-relay/tls/cert.pem android/feature-home/src/main/res/raw/eidolang_relay.pem

     Keeping the existing certificate (pass --cert with the old directory) avoids all of this.

WARN
fi
chmod 600 "$CERT_DIR/key.pem"

echo "== building"
docker build -q -t "$IMAGE" "$INSTALL_DIR" >/dev/null

echo "== (re)starting"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker volume create "$STATE_VOLUME" >/dev/null
docker run -d --name "$NAME" --restart unless-stopped \
  -p 6881:6881/tcp -p 6881:6881/udp -p 6882:6882/tcp \
  -v "$STATE_VOLUME:/var/lib/eidolang-relay" \
  -v "$CERT_DIR:/opt/tls:ro" \
  "$IMAGE" >/dev/null

# A container that is "Up" proves nothing: the relay once sat wedged for four days with the port
# bound and the process alive, answering nobody. Ask it something.
echo "== checking"
for i in $(seq 1 10); do
  sleep 1
  if curl -sk --max-time 5 "https://127.0.0.1:6882/directory/search?q=zz" >/dev/null 2>&1; then
    echo "relay is answering on 6882 (directory + locker) and 6881 (swarm)"
    docker ps --filter "name=$NAME" --format '  {{.Names}}  {{.Status}}'
    exit 0
  fi
done
echo "relay did not answer within 10s; see: docker logs $NAME" >&2
exit 1

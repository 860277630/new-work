#!/usr/bin/env bash
# This starts only the loopback backend. Configure the HTTPS reverse proxy separately.
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run with sudo.\n' >&2; exit 1; }
set -a
source /etc/format-factory/backend.env
set +a
[[ ${AUTH_MODE:-} == wechat && ${HOST:-} == 127.0.0.1 ]] || {
  printf 'Production requires WeChat auth and loopback binding.\n' >&2; exit 1;
}
[[ ${WECHAT_APP_ID:-} =~ ^wx[a-zA-Z0-9]{16}$ && ${#WECHAT_APP_SECRET} -ge 16 ]] || {
  printf 'Fill the real WECHAT_APP_SECRET in /etc/format-factory/backend.env first.\n' >&2; exit 1;
}
install -m 600 /dev/null /etc/format-factory/production-ready
systemctl enable --now format-factory.service
for attempt in $(seq 1 40); do
  if curl --fail --silent http://127.0.0.1:8088/api/health > /dev/null; then
    printf 'WeChat backend is running on loopback. Finish domain, HTTPS and phone verification before public release.\n'
    exit 0
  fi
  sleep 0.5
done
printf 'Backend did not become healthy; check journalctl -u format-factory.service.\n' >&2
exit 1

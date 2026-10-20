#!/usr/bin/env bash
# Run on a new Ubuntu 24.04 x86_64 server: sudo bash install-ubuntu.sh BUNDLE SHA256 REVISION
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run with sudo.\n' >&2; exit 1; }
bundle=$(realpath "${1:?bundle required}")
expected=${2:?SHA256 required}
revision=${3:?Git revision required}
bootstrap_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ $expected =~ ^[a-fA-F0-9]{64}$ && $revision =~ ^[a-f0-9]{7,40}$ ]] || exit 1
[[ $(uname -m) == x86_64 ]] || { printf 'This installation targets x86_64.\n' >&2; exit 1; }
printf '%s  %s\n' "$expected" "$bundle" | sha256sum --check --status
[[ ! -e /etc/systemd/system/format-factory.service && ! -e /etc/nginx/sites-available/format-factory ]] || {
  printf 'Existing deployment found; refusing to overwrite configuration.\n' >&2; exit 1;
}
if ! command -v javac >/dev/null || ! command -v nginx >/dev/null || ! command -v heif-enc >/dev/null || ! command -v node >/dev/null; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless nginx libheif-examples libheif-plugin-x265 nodejs
fi
if ! id formatfactory >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/format-factory --shell /usr/sbin/nologin formatfactory
fi
install -d -m 755 /opt/format-factory/releases
install -d -m 750 -o formatfactory -g formatfactory /var/lib/format-factory
install -d -m 700 /etc/format-factory
release=$(mktemp -d "/opt/format-factory/releases/${revision}-XXXXXXXX")
chmod 755 "$release"
tar --extract --gzip --file "$bundle" --directory "$release" --no-same-owner
chown -R formatfactory:formatfactory "$release"
printf 'Building and testing release %s\n' "$revision"
cd "$release"
sudo -u formatfactory env AUTH_MODE=development JAVA_TOOL_OPTIONS=-Xmx512m sh build.sh --test > build-deploy.log 2>&1
sudo -u formatfactory env AUTH_MODE=development java -Xmx512m -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.ImageFormatsTest --heic >> build-deploy.log 2>&1
grep '^PASS:' build-deploy.log
heif-enc --list-encoders > build/heic-encoders.txt 2>&1
grep -i x265 build/heic-encoders.txt

# Smoke-test the real entry point on loopback, then stop this specific process.
sudo -u formatfactory env AUTH_MODE=development HOST=127.0.0.1 PORT=18088 DATA_DIR="$release/build/deploy-smoke" \
  IDPHOTO_MODELS_DIR="$release/.tools/idphoto" java -Xmx512m -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.Server > build/deploy-smoke.log 2>&1 &
smoke_pid=$!
trap 'kill "$smoke_pid" 2>/dev/null || true; wait "$smoke_pid" 2>/dev/null || true' EXIT
ready=0
for attempt in $(seq 1 40); do
  if curl --fail --silent http://127.0.0.1:18088/api/health > build/deploy-health.json; then ready=1; break; fi
  sleep 0.5
done
[[ $ready -eq 1 ]]
python3 -c 'import json; d=json.load(open("build/deploy-health.json")); assert d["status"]=="ok" and d["idPhotoEnabled"] and d["heicToolsDetected"]; print("PASS: Linux server entry point, local models and HEIC health")'
kill "$smoke_pid"
wait "$smoke_pid" || true
trap - EXIT

# Runtime files are immutable for the service user; uploads only go to /var/lib.
chown -R root:root "$release"
chmod -R go-w "$release"
ln -sfn "$release" /opt/format-factory/current
if [[ ! -e /etc/format-factory/backend.env ]]; then
  umask 077
  monitor_token=$(openssl rand -hex 32)
  cat > /etc/format-factory/backend.env <<EOF
AUTH_MODE=wechat
HOST=127.0.0.1
PORT=8088
DATA_DIR=/var/lib/format-factory
IDPHOTO_MODELS_DIR=/opt/format-factory/current/.tools/idphoto
WECHAT_APP_ID=wx85021ef8fc65df22
WECHAT_APP_SECRET=
MONITOR_TOKEN=$monitor_token
EOF
  unset monitor_token
fi
umask 022
cat > /etc/systemd/system/format-factory.service <<'EOF'
[Unit]
Description=WeChat format factory backend
After=network-online.target
Wants=network-online.target
ConditionPathExists=/etc/format-factory/production-ready

[Service]
Type=simple
User=formatfactory
Group=formatfactory
WorkingDirectory=/opt/format-factory/current
EnvironmentFile=/etc/format-factory/backend.env
ExecStart=/usr/bin/java -Xmx512m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp build/classes:.tools/* formatfactory.Server
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/format-factory
MemoryMax=1536M
TasksMax=256
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
EOF
install -m 700 "$bootstrap_dir/start-production.sh" /etc/format-factory/start-production.sh
install -d -m 755 /var/www/format-factory
cat > /var/www/format-factory/index.html <<'EOF'
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>益伴图片工具 · 部署准备</title><body><h1>益伴图片工具</h1><p>服务器部署准备已完成，正在配置正式域名和微信登录。</p><p>服务尚未开放照片上传，请稍后访问。</p></body></html>
EOF
cat > /etc/nginx/sites-available/format-factory <<'EOF'
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    root /var/www/format-factory;
    index index.html;
    server_tokens off;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;
    location = / { try_files /index.html =404; }
    location = /healthz { default_type application/json; return 200 '{"status":"setup-required"}'; }
    location / { return 503; }
}
EOF
if [[ -L /etc/nginx/sites-enabled/default && $(readlink -f /etc/nginx/sites-enabled/default) == /etc/nginx/sites-available/default ]]; then
  unlink /etc/nginx/sites-enabled/default
fi
ln -s /etc/nginx/sites-available/format-factory /etc/nginx/sites-enabled/format-factory
nginx -t
systemd-analyze verify /etc/systemd/system/format-factory.service
systemctl daemon-reload
systemctl enable --now nginx
systemctl reload nginx
nginx_ready=0
for attempt in $(seq 1 20); do
  if curl --fail --silent http://127.0.0.1/healthz; then nginx_ready=1; break; fi
  sleep 0.5
done
[[ $nginx_ready -eq 1 ]]
printf '\nDeployment staged successfully. Production backend remains gated pending private WeChat configuration and HTTPS.\n'
printf 'Release directory: %s\n' "$release"

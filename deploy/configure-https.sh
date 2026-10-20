#!/usr/bin/env bash
# Configure HTTPS for the gated setup page, without enabling the backend.
# sudo bash configure-https.sh DOMAIN API_DOMAIN EXPECTED_IPV4 [ACME_CONTACT_EMAIL]
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run with sudo.\n' >&2; exit 1; }
domain=${1:?main domain required}
api_domain=${2:?API domain required}
expected_ip=${3:?expected public IPv4 required}
email=${4:-}
for name in "$domain" "$api_domain"; do
  [[ $name =~ ^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$ ]] || exit 1
  getent ahostsv4 "$name" | awk '{print $1}' | grep -Fxq "$expected_ip" || {
    printf 'DNS does not point to the expected server: %s\n' "$name" >&2; exit 1;
  }
done
[[ ! -e /etc/format-factory/production-ready ]] || {
  printf 'Production is enabled; refusing to replace its proxy with a setup page.\n' >&2; exit 1;
}
site=/etc/nginx/sites-available/format-factory
[[ -f $site ]] && grep -q setup-required "$site"
DEBIAN_FRONTEND=noninteractive apt-get install -y certbot
backup="${site}.before-https-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$site" "$backup"
install -d -m 755 /var/lib/letsencrypt/.well-known/acme-challenge
cat > "${site}.new" <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name $domain $api_domain;
    root /var/www/format-factory;
    index index.html;
    server_tokens off;
    location ^~ /.well-known/acme-challenge/ { root /var/lib/letsencrypt; try_files \$uri =404; }
    location = / { try_files /index.html =404; }
    location = /healthz { default_type application/json; return 200 '{"status":"setup-required"}'; }
    location / { return 503; }
}
EOF
install -m 644 "${site}.new" "$site"
if ! nginx -t; then cp -p "$backup" "$site"; exit 1; fi
systemctl reload nginx
token=$(openssl rand -hex 16)
printf '%s' "$token" > "/var/lib/letsencrypt/.well-known/acme-challenge/$token"
for name in "$domain" "$api_domain"; do
  reached=0
  for attempt in $(seq 1 10); do
    body=$(curl --fail --silent --max-time 10 "http://$name/.well-known/acme-challenge/$token") || body=''
    if [[ $body == "$token" ]]; then reached=1; break; fi
    sleep 1
  done
  [[ $reached -eq 1 ]] || { printf 'HTTP validation path is not reachable for %s. Check DNS, firewall or hosting restrictions.\n' "$name" >&2; exit 1; }
done
certbot_args=(certonly --non-interactive --agree-tos --webroot --webroot-path /var/lib/letsencrypt
  --cert-name "$domain" --keep-until-expiring --preferred-challenges http -d "$domain" -d "$api_domain")
if [[ -n $email ]]; then certbot_args+=(--email "$email"); else certbot_args+=(--register-unsafely-without-email); fi
certbot "${certbot_args[@]}"
cat > "${site}.new" <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    root /var/www/format-factory;
    location = / { try_files /index.html =404; }
    location = /healthz { default_type application/json; return 200 '{"status":"setup-required"}'; }
    location / { return 404; }
}
server {
    listen 80;
    listen [::]:80;
    server_name $domain $api_domain;
    server_tokens off;
    location ^~ /.well-known/acme-challenge/ { root /var/lib/letsencrypt; try_files \$uri =404; }
    location / { return 308 https://\$host\$request_uri; }
}
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name $domain $api_domain;
    ssl_certificate /etc/letsencrypt/live/$domain/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$domain/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:FormatFactoryTLS:10m;
    server_tokens off;
    root /var/www/format-factory;
    index index.html;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;
    location = / { try_files /index.html =404; }
    location = /healthz { default_type application/json; return 200 '{"status":"setup-required"}'; }
    location / { return 503; }
}
EOF
install -m 644 "${site}.new" "$site"
if ! nginx -t; then cp -p "$backup" "$site"; nginx -t; systemctl reload nginx; exit 1; fi
systemctl reload nginx
install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/format-factory-nginx <<'EOF'
#!/bin/sh
set -eu
/usr/sbin/nginx -t
/bin/systemctl reload nginx
EOF
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/format-factory-nginx
systemctl enable --now certbot.timer
for name in "$domain" "$api_domain"; do
  curl --fail --silent --retry 5 --retry-delay 1 --retry-connrefused --resolve "$name:443:127.0.0.1" "https://$name/healthz"
  printf '\n'
done
openssl x509 -in "/etc/letsencrypt/live/$domain/cert.pem" -noout -dates -ext subjectAltName
printf 'HTTPS configured. Business endpoints remain gated.\n'

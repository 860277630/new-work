# Ubuntu 服务器部署

目标环境：Ubuntu 24.04 LTS、x86_64、4GB 内存。部署使用 Java 17 和 systemd，HEIC 使用 Ubuntu 的 libheif 与 x265 插件。服务器、域名、微信账号由使用者自行管理。

## 部署包

部署包是 gzip 压缩的 tar，顶层包含项目源码和网页资源，以及以下运行/测试依赖：

- `.tools/pdfbox-app-3.0.7.jar`
- `.tools/gson-2.13.2.jar`
- `.tools/onnxruntime-1.23.2.jar`
- `.tools/idphoto/modnet.onnx` 与 `.tools/idphoto/face-rfb-320.onnx`
- `build/idphoto-test/portrait-fixture.png`：有权使用的正面单人测试照片；用于真实模型测试。

这些二进制依赖与测试照片不提交到 Git。部署包不得包含用户上传照片、`data/`、SSH 私钥或私密环境文件。

上传部署包、`install-ubuntu.sh` 和 `start-production.sh` 到服务器的同一目录后，执行：

```bash
sudo bash install-ubuntu.sh /path/to/bundle.tar.gz <部署包SHA256> <Git提交号>
```

首次安装会校验部署包，在独立目录构建并测试真实转换、HTTP、监控、证件照和 HEIC，然后配置 Nginx 待配置页面及 systemd 服务。已有同名服务配置时会停止，避免覆盖已有部署。中途测试失败时先查看部署日志，不要直接开放公网接口。

## 目录与权限

| 路径 | 用途 |
| --- | --- |
| `/opt/format-factory/releases/` | 独立发布目录，保留测试日志与输出 |
| `/opt/format-factory/current` | 当前发布目录的符号链接 |
| `/var/lib/format-factory` | 用户上传、转换结果和监控日志，仅服务用户可写 |
| `/etc/format-factory/backend.env` | 服务器私密配置，仅 root 可读写，不输出内容到日志 |
| `/etc/format-factory/start-production.sh` | 检查私密配置并启动本机后端 |
| `/etc/systemd/system/format-factory.service` | 后端服务，默认未启用，监听 `127.0.0.1:8088` |
| `/etc/nginx/sites-available/format-factory` | 初始待配置页面，不代理用户接口 |

后端使用独立账号 `formatfactory`、最大 Java 堆 512MB、整个服务 cgroup 最大内存 1536MB。证件照子 JVM 及其原生推理内存也计入该服务的 cgroup。超限可能导致任务或服务被终止，需要结合监控评估限制。

## 域名与微信配置就绪后

1. 完成所需域名/小程序备案，将域名解析到服务器，并配置 HTTPS。
2. 在服务器用 `sudoedit /etc/format-factory/backend.env` 填入真实的 `WECHAT_APP_SECRET`，核对 `WECHAT_APP_ID`。不要将凭据发到聊天或提交 Git。
3. 执行 `sudo /etc/format-factory/start-production.sh`。服务只监听回环地址；启动不代表已完成公网发布。
4. 将 Nginx 配置改为正式 HTTPS 反向代理：目标 `http://127.0.0.1:8088`，上传限制至少 20MB，转换请求设置合适的超时；监控管理接口需保护。先 `sudo nginx -t`，通过后再 reload。
5. 小程序 `apiBase` 改为正式 HTTPS 域名，在微信后台配置 request / downloadFile 合法域名、隐私指引，重新上传体验版并真机测试。
6. 公开运营前补齐 README 中列出的持久化、限流和存储总量限制。当前内存中的任务/文件元数据在服务重启后不恢复；仅持久化上传目录并不能解决这一点。

常用检查：

```bash
sudo systemctl status format-factory.service --no-pager
sudo journalctl -u format-factory.service -n 50 --no-pager
curl --fail http://127.0.0.1:8088/api/health
```

初始公网 `/healthz` 返回 `setup-required`，表示服务器等待正式配置；它不代表后端已公开可用。当前脚本不自动配置域名、证书或云厂商防火墙，不修改 SSH 登录方式和账号密码。

## 给待配置页面启用 HTTPS

在域名及 API 子域名的 A 记录都指向服务器后，可单独上传 `configure-https.sh`，执行：

```bash
sudo bash configure-https.sh magicboxhub.space api.magicboxhub.space 110.40.207.112
```

脚本只处理 Nginx 待配置页面，安装 Certbot，通过 HTTP-01 验证申请同时覆盖两个域名的证书，配置 HTTP 跳转 HTTPS、TLS 1.2/1.3 和系统自动续期。运行前备份 Nginx 站点文件，配置校验失败时恢复备份；不会启用正式后端，也不会修改服务器登录方式。已启用正式后端时拒绝覆盖配置。

默认不填写 ACME 联系邮箱；如需使用自己的联系邮箱，可作为第四个参数提供。证书私钥和 ACME 账户数据位于服务器的 `/etc/letsencrypt/`，不能提交 Git。域名和子域名保持解析、TCP 80/443 可访问，是 HTTP 验证和后续使用的前提。若接入商因备案状态限制 HTTP 访问，应按其指引完成备案或使用正规的 DNS 验证流程。

腾讯云轻量服务器还需在实例的“防火墙”页签放通公网 TCP 443（HTTPS）；服务器本机 Nginx 正在监听并不代表云防火墙已放行。公网 IPv4 服务的规则可设为来源 `0.0.0.0/0`、TCP、443、允许。保留用于证书验证的 TCP 80，Java 的 8088 继续只监听回环地址。

常用证书检查：

```bash
sudo certbot certificates
sudo certbot renew --dry-run
sudo systemctl status certbot.timer --no-pager
```

HTTPS 配置成功不等于网站或小程序已经备案，也不等于工具接口已对外开放。正式域名加入微信后台、AppSecret 填写、用户隐私授权及真机验证仍需按上文继续完成。

# 格式工坊：微信小程序第一版

原生微信小程序 + Java 17 后端。无需 Word、VBA、.NET、Python 或 Maven。BMP/DIB、JPEG、GIF、TIFF、PNG 使用 Java 编解码；HEIC 调用项目内的 libheif 工具与 x265；证件照使用本地 ONNX Runtime 模型推理。已实现真实转换：

| 功能 | 输入 | 输出 |
| --- | --- | --- |
| 图片压缩 | 一张 BMP/DIB、JPEG、GIF、TIFF、PNG、HEIC | 缩放、按质量编码的 JPG；透明区域填白 |
| 图片格式转换 | 一张上述图片 | BMP、DIB、JPEG、GIF、TIFF、PNG、HEIC 任意互转 |
| 图片转 PDF | 1–9 张上述图片 | 按选择顺序每张一页，A4 居中适配 |
| PDF 转图片 | 最多 20 页的未加密 PDF | 每页一张 JPG / PNG，打包 ZIP |
| 证件照制作 | 一张 JPG、PNG 或 HEIC 正面单人照片 | 自动抠图、红白蓝换底、按尺寸裁剪，导出 JPG / PNG（300 DPI） |

单文件最多 20MB，单张图片最多 2400 万像素。GIF **只取首帧**，TIFF **只取第一页**，输出 GIF 为静态图，TIFF 为单页；界面在上传前明确说明。HEIC 输出是 HEVC 编码的真实 HEIC 文件，输入取第一张静态图。PNG/TIFF 保留支持的透明通道；JPEG、BMP/DIB、HEIC 填白色背景，GIF 最多 256 色且不保留连续半透明效果。

JPEG 输入接受 `.jpg/.jpeg/.jpe/.jfif`，统一输出 `.jpg`；TIFF 输入接受 `.tif/.tiff`，统一输出 `.tiff`。BMP 和 DIB 可选 **1 位黑白、4 位（16 色）、8 位（256 色）、24 位真彩色**；低色数输出会量化颜色。DIB 输出为真实的 DIB 数据（不带 BMP 的 14 字节文件头），支持读取原始 DIB 及使用 `.dib` 扩展名的 BMP 文件。格式转换保持尺寸，图片压缩按最大宽度缩小。

转换后文件不保证比原文件更小。转换不保留图片 EXIF 等元数据，JPEG 手机照片的 EXIF 旋转尚未自动处理；HEIC 由 libheif 处理容器中的旋转信息。暂不支持 WebP、Office、音视频、OCR 或 PDF 转 Word。

## Windows 本地运行

### 当前微信开发预览

本项目已配置真实 AppID，并将小程序导航栏名称设为“益伴图片格式转换”。当前 `miniprogram/config.js` 使用 `http://192.168.0.2:8088`，供本机开发者工具和同一局域网的手机测试；电脑 IP 变化时需要同步修改，随后重新编译、预览或上传开发版。

手机预览时，保持手机与电脑处于同一局域网，通过下面的命令启动后端（监听所有网络接口，只用于开发调试）：

```powershell
cd D:\project\wechat-format-factory
.\run-dev.cmd -JavaHome 'D:\Java\jdk-17.0.20'
```

如已有仅监听 `127.0.0.1` 的后端，先停止该实例再运行以上命令。手机浏览器访问 `http://192.168.0.2:8088/api/health`，确认可连通，再扫描开发者工具生成的预览二维码。二维码及编译包大小记录保存于 `build/wechat/`。本地 HTTP 地址只能用于开发调试，正式发布前需部署 HTTPS 后端、开启微信登录并配置合法域名。

微信开发者工具模拟器出现长时间无响应时，可正常退出工具，随后用官方 CLI 的 `open --project <项目路径> --disable-gpu` 参数尝试重新启动；该参数不更改 Windows 显卡设置。本次导入与预览编译已在禁用 GPU 的启动方式下完成。命令行使用前需登录工具，并由用户在“设置 → 安全设置”开启服务端口。

安装 JDK 17，确保 `javac` 在 PATH；Windows 脚本会使用该编译器旁的 `java.exe`，避免 PATH 中旧版 Java 8 抢先。也可显式传入 `-JavaHome 'D:\Java\jdk-17.0.20'`。首次配置 HEIC 需要 Node.js 22.15+（推荐 24）解压 Zstandard 包；Node 也用于前端测试，后端运行时不依赖 Node。

```powershell
cd D:\project\wechat-format-factory
.\setup-heic.cmd
.\build.cmd -JavaHome 'D:\Java\jdk-17.0.20' -Test
.\run.cmd -JavaHome 'D:\Java\jdk-17.0.20'
```

如果直接运行 `.\run.ps1` 提示“此系统上禁止运行脚本”，请使用上面的 `run.cmd`。它为新启动的 PowerShell 进程指定 `ExecutionPolicy Bypass`，不更改系统或用户的长期执行策略。也可直接运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -JavaHome 'D:\Java\jdk-17.0.20'
```

单位电脑如果由组策略强制限制脚本，进程级参数可能无法覆盖。当前工作区已构建后端，也可直接启动 Java，完全不执行 PowerShell 脚本（先进入项目目录）：

```powershell
& 'D:\Java\jdk-17.0.20\bin\java.exe' '-Xmx512m' '-Djava.awt.headless=true' '-cp' 'build/classes;.tools/*' 'formatfactory.Server'
```

如果出现 `Address already in use`，表示服务端口已被占用。新版 `run.cmd` 会检查健康接口；如果已有本项目的正常实例，会提示 `already running` 并退出启动脚本，不重复启动 Java。停止旧实例后才能应用重新编译的代码或新的启动配置。在启动后端的终端按 Ctrl+C 可停止前台实例。

也可选择其他服务端口：

```powershell
.\run.cmd -JavaHome 'D:\Java\jdk-17.0.20' -ServicePort 8089
```

此时将 `miniprogram/config.js` 中的 `apiBase` 同步改成 `http://127.0.0.1:8089`。排查占用可运行 `netstat -ano | findstr :8088`，只处理经过确认的本项目进程，不要批量结束所有 Java 进程。

首次构建下载 PDFBox 3.0.7、Gson 2.13.2 与 ONNX Runtime 1.23.2。当前工作区已下载这三个依赖。后端默认监听 `http://127.0.0.1:8088`。请保持后端终端运行。

**当前工作区的 HEIC 工具已配置，不必重复执行 setup-heic.cmd。** 换电脑或删除 `.tools` 后，运行一次该脚本：从 [MSYS2 官方镜像](https://packages.msys2.org/packages/mingw-w64-ucrt-x86_64-libheif) 获取 libheif 和依赖，逐包校验索引里的 SHA256，解压至 `.tools/heic`，保留许可证和版本清单，不更改系统 PATH、不安装系统服务。本次验证版本为 libheif 1.23.6 / x265 4.3。也可设置 `HEIF_TOOLS_DIR` 指向包含 `heif-enc.exe` 和 `heif-dec.exe` 及所需 DLL 的目录。普通格式不需要这些工具；缺失时 HEIC 转换会给出配置说明。

## 电脑浏览器页面

已经提供网页界面，在本地开发模式运行后，使用 Chrome / Edge 打开 **http://127.0.0.1:8088/**。支持四项图片/PDF 转换、拖放上传、图片 PDF 排序、参数调整、任务进度、结果下载、历史任务及主动删除。首页另有“证件照制作”入口，打开独立页面 `/idphoto`。网页和 API 使用同一个服务端口，不需要 npm、前端开发服务器或额外部署。

如果升级前后端已在运行，先在原终端按 Ctrl+C，再执行 `build.cmd` 和 `run.cmd`。仅刷新浏览器不能让旧 Java 进程加载新的后端路由。静态文件会随构建复制到 `build/classes/web`，后端从 classpath 读取，Docker / Linux 构建也包含这些资源。

浏览器版沿用开发模式会话，仅用于本地体验。`AUTH_MODE=wechat` 的正式服务只接受微信登录，浏览器页不会创建绕过微信身份验证的会话。浏览器页面当前没有微信激励广告。

提供实际 Edge 浏览器测试：四种工具的转换和下载、所有扩展图片格式及 BMP/DIB 四种位深、结果重新上传转 PNG、上传同意确认、图片排序、历史任务、删除、损坏文件错误提示及手机宽度布局。截图位于 `build/browser-test/`。可选自动化脚本为 `tests/browser.test.js`，需可用的 Playwright 和浏览器，不是运行网页的依赖。

在微信开发者工具中导入 `miniprogram` 文件夹，填入自己的 AppID。默认 `touristappid` 仅用于允许的本地体验；登录、文件选择和广告等能力需使用真实 AppID 并在真机确认。开发阶段设置中可关闭合法域名检查，正式发布必须开启并配置 HTTPS 合法域名。

微信后台填写小程序头像时，可上传 `assets/branding/wechat-avatar-144.png`：项目现有图标的 PNG 导出，144×144 像素、约 3.4KB，不带名称。另有 `wechat-avatar-512.png` 高清版本，两张都小于 2MB。需要重新导出时，可选运行 `node scripts/export-avatar.js`（需 Playwright 和可用浏览器；应用运行不依赖此导出工具）。

## 证件照制作

**当前工作区的两个证件照模型与 ONNX Runtime 已配置。** 小程序首页点击“证件照制作”，或在电脑浏览器打开 `http://127.0.0.1:8088/idphoto`。更新代码后先构建，再停止旧后端并重新启动；小程序在微信开发者工具里重新编译。

换电脑时，在项目目录执行一次 `setup-idphoto.cmd`（Windows，需要 Node.js）或 `sh setup-idphoto.sh`（Linux，需要 curl、sha256sum），联网下载固定版本模型。脚本校验 SHA256，Windows 脚本另校验文件大小，已有文件校验通过时跳过下载。随后构建并启动。模型默认放在 `.tools/idphoto`，也可通过 `IDPHOTO_MODELS_DIR` 指定目录，其中需有 `modnet.onnx` 与 `face-rfb-320.onnx`。模型来源与许可见 `licenses/idphoto/README.md`。普通图片和 PDF 转换不需要这些模型。

先选择只有一个人、头顶完整、无遮挡的正面半身照片，并确认同意上传，再点击“生成预览”。支持相册、拍摄、微信聊天照片和微信聊天图片附件；输入 JPG/JPEG、PNG、HEIC，最大 20MB / 2400 万像素。JPEG 的 EXIF 方向会在证件照处理前纠正，HEIC 依赖已配置的 libheif 工具。使用本服务服务器上的本地模型抠图与定位，不将照片发送给第三方 AI 服务。

可选择白、蓝、红底，一寸（25×35mm）、二寸（35×49mm）、小二寸（33×48mm），或自定义宽 20–100mm / 高 20–120mm。支持缩放 80–150%、水平/上下微调，修改后重新生成。页面统一输出 300 DPI 的 JPG 或 PNG，一寸为 295×413 像素、二寸为 413×579 像素。保留原有面部特征，输出会去除原照片元数据，仅写入所需 DPI。请检查发丝、耳朵、头顶留白及目标用途要求，预设尺寸和自动裁剪不保证通过具体审核。

单次制作在独立 Java 子进程运行，最大 Java 堆 384MB，超时 75 秒结束子进程并清理中间文件；原生推理内存不计入 Java 堆。小程序提交任务后立即保存到“最近任务”，离开制作页仍可查看状态和结果；下载失败可重试下载并发送。网页可下载结果，预览下载失败时有重试按钮。两端在处理失败后也能主动删除照片；同一页面更换照片、重复生成后，删除按钮会清理本页跟踪的所有上传与结果。网页刷新或离开后不恢复证件照页面状态，未主动删除的文件按 24 小时规则清理。

模型未配置、照片无人脸、多人脸或人脸太小时会显示错误。API 还支持 `photoDpi` 150–600，页面固定为 300。Docker 使用 `/app/models`：先在主机配置模型，再以只读方式挂载 `.tools/idphoto` 到 `/app/models`；镜像不会自行下载模型。Ubuntu 24.04 x86_64 的直接 Java 部署已于 2026-10-20 完成真实模型与 HEIC 测试；Docker 容器推理尚未实机验证。

真机访问不了电脑的 `127.0.0.1`。手机与电脑连接同一局域网，将 `miniprogram/config.js` 的 `apiBase` 改为电脑局域网地址，例如 `http://192.168.1.10:8088`，并在后端启动前设置 `$env:HOST='0.0.0.0'`。按实际环境配置防火墙和调试方式。

## CPU 与内存监控

重新编译并停止旧服务后启动，打开 **http://127.0.0.1:8088/monitor**。独立的管理员页面显示系统/容器 CPU、系统/容器内存、Java 堆内存、Java 进程 CPU、转换任务数、最近 120 次采样趋势及告警记录，可导出当前采样 CSV。

本次默认配置为 **CPU 80%、系统内存 85%、Java 堆内存 85%、每 5 秒采样、连续超限 3 次告警**。达到阈值也计为超限，持续超限每 300 秒提醒；低于阈值至少 5 个百分点时记录恢复。例如 CPU 告警后降到 79% 仍保持告警，降到 75% 才恢复，避免临界值波动反复告警。采样缺失或失败不会被当成 0% 或恢复。

可以在页面修改三个阈值、采样间隔和连续次数，点击“保存并应用”立即生效，设置写入 `data/monitor/settings.json`，重启后读取。修改设置会重新开始连续超限计数。配置优先级为 **启动环境变量 > 保存的设置 > 默认值**，因此环境变量固定了某项时，网页修改仅对当前运行生效，重启会恢复该环境变量的值。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| MONITOR_CPU_PERCENT | 80 | 系统/容器 CPU 告警阈值，1–100% |
| MONITOR_MEMORY_PERCENT | 85 | 系统/容器内存告警阈值，1–100% |
| MONITOR_HEAP_PERCENT | 85 | Java 堆内存告警阈值，1–100% |
| MONITOR_INTERVAL_SECONDS | 5 | 采样间隔，1–300 秒 |
| MONITOR_CONSECUTIVE_SAMPLES | 3 | 连续超限次数，1–60 |
| MONITOR_COOLDOWN_SECONDS | 300 | 持续超限提醒间隔，10–86400 秒 |
| MONITOR_RETENTION_DAYS | 7 | 日志保留天数，1–90 天 |
| MONITOR_MAX_LOG_MB | 20 | 单日志文件轮转大小，最多 100 MB |
| MONITOR_DIR | DATA_DIR/monitor | 日志及设置目录 |
| MONITOR_TOKEN | 未设置 | 管理员令牌，设置时至少 32 个字符 |

每次采样写入 `metrics-日期-实例ID-分段.jsonl`，告警、持续提醒、恢复和设置变更写入 `alerts-日期-实例ID-分段.jsonl`；日期为 UTC，每行是一个 JSON 对象，及时刷新到文件。日志按日期/大小轮转，每小时清理超过保留天数的监控日志。清理不涉及设置文件及用户文件。页面仅展示当前进程最近一小时的采样和最近 100 条事件；历史日志保留在磁盘上，重启后不会重新载入页面。监控运行在独立线程，采样/日志失败会提示并重试。

当前告警通过**监控页面、后端终端和本地日志**提供，尚未接入微信、邮件或短信通知。Java 堆指标是已用堆 / 最大堆，不等于 Java 进程总内存；HEIC 原生子进程也不属于 Java 堆。Windows 使用 JDK 的操作环境指标；Linux 主机优先使用 `MemAvailable` 计算内存，标准 cgroup v2 容器有内存上限时按容器用量/上限计算，包含缓存及子进程，其他环境回退到 JDK 指标。页面注明实际统计来源；本次实测环境为 Windows。

本机开发模式且未设置令牌时，仅来自回环地址的请求可以访问监控 API；局域网访问需令牌。设置令牌后，本机访问也需要输入。正式部署应使用 `AUTH_MODE=wechat` 并设置独立的 `MONITOR_TOKEN`，普通用户的会话令牌不能访问监控。管理员令牌只放在服务器环境变量，不能放到小程序代码；页面通过 Authorization 请求头发送，不保存到网址、localStorage 或 sessionStorage。

可以在 PowerShell 启动前生成一次管理员令牌（保留其值，网页授权时输入）：

```powershell
$taskMonitorBytes = New-Object byte[] 32
$taskMonitorRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$taskMonitorRandom.GetBytes($taskMonitorBytes)
$taskMonitorRandom.Dispose()
$env:MONITOR_TOKEN = [Convert]::ToBase64String($taskMonitorBytes)
$env:MONITOR_TOKEN
.\run.cmd -JavaHome 'D:\Java\jdk-17.0.20'
```

这个环境变量只作用于当前终端和它启动的程序，下次从其他终端启动需重新配置。正式部署通过服务或容器的私密环境配置管理令牌。Docker 需要给运行用户 UID 10001 对日志目录的写权限，并持久化 `/app/data`（或指定的 `MONITOR_DIR`），否则删除容器会丢失记录。正式服务通过 HTTPS 访问管理员页面，监控无需单独开放端口。

## 调试：先用自己的电脑，不需要购买服务器

先运行 `run.ps1`，浏览器访问 `http://127.0.0.1:8088/api/health`，看到 `status: ok` 即后端正常。微信开发者工具导入 `D:\project\wechat-format-factory\miniprogram`，填写 AppID，保持 `config.js` 的默认本机地址。开发者工具“详情 → 本地设置”关闭开发环境合法域名检查后编译。前端断点在调试器的 Sources 中设置；Network 可查看 `/api/session`、`/api/files`、`/api/jobs` 请求及返回的错误。

Java 后端断点调试：

```powershell
.\run.cmd -EnableDebugger -JavaHome 'D:\Java\jdk-17.0.20'
```

在 IDEA 打开 `backend/src` 作为源码目录，新建运行配置“Remote JVM Debug”，主机 `127.0.0.1`，端口 `5005`，启动调试连接，在 `Server.java` 的 `route` 或 `convert` 中设置断点。后端默认立即运行，不等待调试器。可以用 `-DebuggerPort 5006` 更换调试端口，调试端口只监听本机。

真机测试：后端启动前设置 `$env:HOST='0.0.0.0'`；用 `ipconfig` 查看电脑 Wi-Fi/以太网的 IPv4 地址；将 `config.js` 的 `apiBase` 改为该地址，例如 `http://192.168.1.10:8088`。手机和电脑连接同一 Wi-Fi，先用手机浏览器访问 `/api/health`，确认连通，再在开发者工具中编译并预览。必要时在手机的小程序菜单中打开调试并重新进入。防火墙仅对当前可信专用网络放行 Java/8088，不要关闭整个防火墙。

常见错误：`ERR_CONNECTION_REFUSED` 通常是后端未启动或端口错误；真机连接超时检查 Wi-Fi、主机监听地址和防火墙；`url not in domain list` 检查开发调试设置/正式域名；`401` 重新打开小程序获取会话；上传超限或损坏文件会显示服务端错误说明。

局域网地址只适合开发测试。正式提供服务需要稳定公网接入、HTTPS 域名和微信域名配置；如果使用中国内地公网云服务器，还需按接入商要求办理网站/域名备案，小程序备案另行办理。现有主机可以承担转换工作，但家用网络需确认公网接入方式、可用端口、上行带宽、持续开机与接入商规定。内网穿透适合临时调试，正式使用需固定域名、HTTPS 和相应备案/接入条件。

## Linux / 云托管部署

Ubuntu 服务器的 systemd 部署、权限目录和待配置流程见 [deploy/README.md](deploy/README.md)。当前实际部署进度见 `STATUS.md`；域名、HTTPS 和微信私密配置完成前，公网只提供待配置页面。

```sh
# Ubuntu / Debian：仅 HEIC 需要；软件包应提供 x265 编码器。
sudo apt-get update
sudo apt-get install libheif-examples
sh build.sh
sh run.sh
```

Linux 使用 PATH 中的 `heif-enc`、`heif-dec`（旧版名为 `heif-convert`）；也可设置 `HEIF_TOOLS_DIR`。先运行 `heif-enc --list-encoders`，确认 HEIC 分类包含 x265。Dockerfile 已增加 libheif 工具安装。使用部署系统维护的安全更新版本。

提供 Dockerfile，可用 JDK 17 构建、JRE 17 运行；容器以普通用户运行。Docker 构建与 Linux 环境本次未实际执行。

部署时设置 `AUTH_MODE=wechat`、`WECHAT_APP_ID`、`WECHAT_APP_SECRET`，通过微信登录 code 在服务端交换 openid，禁止将 AppSecret 放在小程序代码中。默认开发模式不验证微信身份，只适合本地调试。Docker 镜像默认要求微信身份配置，没有真实凭据不会启动。

其他环境变量：`HOST`、`PORT`、`DATA_DIR`、`HEIF_TOOLS_DIR`。HEIC 采用独立进程，每次调用最多 90 秒，超时/中断会结束对应子进程并清理中间文件；x265 使用 fast 预设与 2 个池线程。公网使用需配置 HTTPS 接入、上传/请求超时、用户与 IP 限流、存储总量限制，并将 PDF/Java 图像处理放入具有内存与时间限制的独立进程；当前 MVP 使用单工作线程，PDF/Java 图像转换尚未实现硬超时和进程隔离。

文件元数据、会话和任务状态存于内存，重启后历史任务不可恢复。适合本地验证，不是已部署的生产服务。正式运营应接入持久化数据库、对象存储和任务队列。

## 文件操作与隐私

聊天中直接发送的照片使用 `wx.chooseMessageFile` 的 `type: 'image'`，不会再被文件类型筛选排除；以附件发送的图片通过独立的“从微信聊天选择图片文件”入口选择，使用 `type: 'file'` 和图片扩展名过滤。PDF 使用文件模式并仅筛选 PDF。图片也可从相册选择。TIFF/DIB/HEIC 建议从图片文件入口选择，系统相册选择器可能自行转码。用户确认后上传。转换完成后可预览 JPG/PNG/PDF，或通过 `wx.shareFileMessage` 将结果发送到微信聊天；TIFF/DIB/HEIC 等格式和 ZIP 下载后作为文件发送，需要支持相应格式的应用打开。网页对不能预览的格式显示格式标识，不影响上传转换。

临时文件到期时间为创建后 24 小时，每 5 分钟清理一次。用户可主动删除原文件和结果。转换过程中返回首页不会中断服务端任务，可在“最近任务”查看。上传失败留下的临时文件由定时清理回收。正式发布需填写与实际数据处理一致的用户隐私保护指引及小程序备案信息。

## 广告与收益

当前图片/PDF 转换与证件照功能免费使用，**没有接入真实广告，没有广告收益，也没有启用广告次数门槛**。已提供 `utils/rewarded-ad.js` 适配器，可区分完成观看与提前关闭；它没有被业务页面调用。

后续流程：开通流量主 → 创建激励视频广告位 → 填入真实 adUnitId → 实现服务端额度/奖励策略 → 真机测试 → 提交审核。客户端 `isEnded` 回调可用于正常交互判断，不能作为服务端可信观看凭证。正式额度方案应研究账号可用的官方验证能力，并处理重复领取、广告加载失败与转换失败退回次数。

## API

文件和任务接口携带用户会话 `Authorization: Bearer <token>`。两个管理员监控接口按上文使用独立的 `MONITOR_TOKEN`（仅本机开发模式未配置令牌时允许免令牌）；健康检查和创建会话无需登录。

| 方法 | 地址 | 说明 |
| --- | --- | --- |
| GET | /api/health | 能力与登录模式 |
| POST | /api/session | 本地 `{deviceId}` / 微信 `{code}` |
| POST | /api/files?name=image.png | 原始二进制文件体，不是 multipart |
| POST | /api/jobs | JSON 转换请求 |
| GET | /api/jobs/{id} | 查询任务与结果文件 ID |
| GET | /api/files/{id} | 下载，校验文件所属用户 |
| DELETE | /api/files/{id} | 删除 |
| GET | /api/admin/metrics?limit=120 | 管理员监控，最近采样数量 1–3600，默认 60 |
| PUT | /api/admin/monitor-settings | 管理员修改并保存监控设置 |

任务请求示例：

```json
{"operation":"pdfToImages","fileIds":["uploaded-id"],"format":"png","dpi":120,"quality":75}
```

位图示例：`{"operation":"convertImage","fileIds":["uploaded-id"],"format":"bmp","bmpBits":4}`，表示输出 16 色 BMP。`format` 可选 `jpg/png/bmp/dib/gif/tiff/heic`，`bmpBits` 默认 24，可选 `1/4/8/24`。PDF 转图片仍支持 JPG/PNG ZIP。

证件照示例：`{"operation":"idPhoto","fileIds":["uploaded-id"],"format":"jpg","quality":95,"photoBackground":"blue","photoWidthMm":25,"photoHeightMm":35,"photoDpi":300,"photoZoom":1,"photoOffsetX":0,"photoOffsetY":0}`。背景仅 `white/blue/red`，输出仅 `jpg/png`，输入限 JPG/JPEG、PNG、HEIC。`GET /api/health` 的 `idPhotoEnabled` 表示两个模型文件存在，实际推理时还会验证模型哈希与运行库。

监控设置请求必须包含全部五项，且均为 JSON 数值：`{"cpuPercent":80,"memoryPercent":85,"heapPercent":85,"intervalSeconds":5,"consecutiveSamples":3}`。`intervalSeconds` 和 `consecutiveSamples` 必须为整数。

## 验证

`build.cmd -JavaHome 'D:\Java\jdk-17.0.20' -Test` 检查真实压缩/尺寸、PNG 透明度、图片 PDF 页数、多页 PDF 渲染 ZIP 和页面内容、损坏文件、页数限制、跨用户文件隔离和原文件不变。`ImageFormatsTest` 验证 13 种格式/位深组合的 169 次交叉转换（含 HEIC）、真实文件签名、BMP/DIB 位深及行填充、纯色图片、扩展名别名，以及双帧 GIF/双页 TIFF 取第一页。未配置 HEIC 时仅运行 144 次普通格式组合；Linux 配置工具后可直接运行 `java -cp 'build/classes:.tools/*' formatfactory.ImageFormatsTest --heic`。

HTTP 测试覆盖登录、上传、异步任务查询、下载、删除、非法请求、扩展图片后缀、BMP 位深参数与下载 MIME，以及证件照静态资源、输入格式、尺寸、底色、缩放和跨用户权限。Node 检查小程序 JS/JSON 与页面资源、广告完成/提前退出逻辑，以及证件照离页任务记录、下载重试、更换照片后的清理、删除中断后的重试。证件照页面的微信开发者工具编译和真机操作尚未验证。

`IdPhotoTest` 使用 `build/idphoto-test/portrait-fixture.png` 与真实本地模型，验证三种底色、一寸/二寸像素、JPG/PNG 的 300 DPI、原图不变、无人脸和多人脸拒绝；缺少模型或测试照片时明确输出 SKIP。当前工作区保留了测试照片，换电脑需自行提供有权使用的清晰单人照片；多人脸测试会将该图拼接成两个人。

可选 `tests/idphoto-browser.test.js` 用独立临时后端实测 Edge：上传同意、真实生成与下载、底色/格式/尺寸调整、预览下载失败重试、无效尺寸、无人脸错误、更换照片后的完整删除和手机宽度布局。需先构建、运行 `IdPhotoTest` 准备测试图，并提供 Playwright 与 Edge/Chromium；截图、下载样例和报告位于 `build/idphoto-browser/`。这些依赖只用于测试。

`ResourceMonitorTest` 使用可控时钟验证连续超限、持续提醒、恢复回差、无效/缺失采样、设置校验与持久化、日志轮转与清理、日志写入失败后的恢复及管理员访问规则，不向实际 CPU 施压。HTTP 测试同时检查监控页面、采样接口和设置保存。可选 `tests/monitor-browser.test.js` 用独立临时服务实测 Edge 授权、自动刷新、告警、编辑保留、保存、CSV 下载和手机布局，截图在 `build/monitor-browser/`；需要 Playwright 和浏览器。

依赖许可：PDFBox/Gson 为 Apache-2.0；libheif 库为 LGPL-3.0，示例命令行工具为 MIT，x265 为 GPL-2.0 或商业许可。部署使用外部工具，若分发工具包，应保留 `.tools/heic/ucrt64/share/licenses` 内的许可，并按对应许可履行源码提供等要求。版本与校验清单位于 `.tools/heic/manifest.json`。

官方参考：[libheif](https://github.com/strukturag/libheif)、[MSYS2 libheif](https://packages.msys2.org/packages/mingw-w64-ucrt-x86_64-libheif)、[PDFBox](https://pdfbox.apache.org/)、[微信文件选择](https://developers.weixin.qq.com/miniprogram/dev/api/media/image/wx.chooseMessageFile.html)、[微信激励视频广告](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/ad/rewarded-video-ad.html)。

监控参考：[JDK 17 操作系统指标](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html)、[JDK 堆内存指标](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/MemoryMXBean.html)、[Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)。

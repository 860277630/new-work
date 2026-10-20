package formatfactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Java 17 local MVP. Real conversion; advertising rewards deliberately disabled. */
public final class Server implements AutoCloseable {
    static final Gson JSON = new Gson();
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final long MAX_PIXELS = 24_000_000;
    static final long TTL = 24 * 60 * 60 * 1000L;
    final Path data;
    final Map<String, Session> sessions = new ConcurrentHashMap<>();
    final Map<String, StoredFile> files = new ConcurrentHashMap<>();
    final Map<String, Job> jobs = new ConcurrentHashMap<>();
    final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8));
    final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
    final String authMode = env("AUTH_MODE", "development");
    final String appId = env("WECHAT_APP_ID", "");
    final String secret = env("WECHAT_APP_SECRET", "");
    final String monitorToken = env("MONITOR_TOKEN", "");
    final ResourceMonitor monitor;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    record Session(String owner, long expires) {}
    record StoredFile(String id, String owner, Path path, String name, long size, long created) {}
    static final class Job {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final long created = System.currentTimeMillis();
        volatile String state = "queued", message = "等待处理", resultId;
        volatile int progress;
        Job(String owner) { this.owner = owner; }
    }
    static final class Request {
        String operation, format;
        List<String> fileIds;
        int quality = 75, maxWidth = 2400, dpi = 120, bmpBits = 24;
        int photoWidthMm = 25, photoHeightMm = 35, photoDpi = 300;
        double photoZoom = 1, photoOffsetX = 0, photoOffsetY = 0;
        String photoBackground = "white";
    }
    static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }

    Server(Path data) throws IOException {
        this.data = data.toAbsolutePath().normalize();
        Files.createDirectories(this.data);
        if (!Set.of("development", "wechat").contains(authMode))
            throw new IllegalArgumentException("AUTH_MODE must be development or wechat");
        if (authMode.equals("wechat") && (appId.isBlank() || secret.isBlank()))
            throw new IllegalArgumentException("WECHAT_APP_ID and WECHAT_APP_SECRET are required");
        ImageIO.setUseCache(false);
        if (!monitorToken.isBlank() && monitorToken.length() < 32)
            throw new IllegalArgumentException("MONITOR_TOKEN must contain at least 32 characters");
        monitor = ResourceMonitor.start(this.data.resolve("monitor"), () -> new int[]{worker.getActiveCount(), worker.getQueue().size()});
        cleaner.scheduleWithFixedDelay(this::cleanup, 5, 5, TimeUnit.MINUTES);
        cleanup();
    }
    public static void main(String[] args) throws Exception {
        var app = new Server(Path.of(env("DATA_DIR", "data")));
        var address = new InetSocketAddress(env("HOST", "127.0.0.1"), Integer.parseInt(env("PORT", "8088")));
        HttpServer server;
        try { server = HttpServer.create(address, 16); }
        catch (BindException exception) {
            app.close();
            System.err.println("Port " + address.getPort() + " is already in use. Stop the existing service or select another PORT.");
            System.exit(1);
            return;
        }
        catch (IOException exception) { app.close(); throw exception; }
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", app::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); app.close(); }));
        server.start();
        System.out.println("Format factory listening on http://" + address.getHostString() + ":" + address.getPort());
        System.out.println("Auth mode: " + app.authMode + "; advertising rewards: disabled");
        System.out.println("Resource monitor: /monitor; logs: " + app.monitor.config.directory());
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Problem problem) {
            reply(exchange, problem.status, Map.of("error", problem.getMessage()));
        } catch (Exception exception) {
            System.err.println("Request failed: " + exception.getClass().getSimpleName());
            reply(exchange, 500, Map.of("error", "处理失败，请稍后重试"));
        } finally { exchange.close(); }
    }
    void route(HttpExchange e) throws Exception {
        String path = e.getRequestURI().getPath();
        String method = e.getRequestMethod();
        if (!path.startsWith("/api/")) {
            serveWeb(e, path, method);
            return;
        }
        if (path.equals("/api/health") && method.equals("GET")) {
            var health = new LinkedHashMap<String, Object>();
            health.put("status", "ok"); health.put("application", "wechat-format-factory"); health.put("authMode", authMode);
            health.put("adsEnabled", false); health.put("maxFileBytes", MAX_BYTES); health.put("maxPdfPages", 20); health.put("expiresHours", 24);
            health.put("imageFormats", List.of("jpg", "png", "bmp", "dib", "gif", "tiff", "heic"));
            health.put("bitmapDepths", List.of(1, 4, 8, 24)); health.put("frameSelection", "first");
            health.put("heicToolsDetected", ImageCodec.toolFound("heif-enc") && ImageCodec.toolFound("heif-dec"));
            health.put("idPhotoEnabled", IdPhoto.available());
            reply(e, 200, health);
            return;
        }
        if (path.equals("/api/admin/metrics") || path.equals("/api/admin/monitor-settings")) {
            if (!monitorAuthorized(authMode, e.getRemoteAddress().getAddress(), monitorToken, e.getRequestHeaders().getFirst("Authorization")))
                throw new Problem(403, "监控仅供管理员使用：请配置并输入 MONITOR_TOKEN；本机开发模式可直接访问");
            if (path.equals("/api/admin/metrics") && method.equals("GET")) {
                int limit;
                try { limit = Integer.parseInt(query(e.getRequestURI()).getOrDefault("limit", "60")); }
                catch (NumberFormatException ex) { throw new Problem(400, "采样数量无效"); }
                require(limit >= 1 && limit <= 3600, "采样数量应在 1–3600 之间");
                reply(e, 200, monitor.view(limit)); return;
            }
            if (path.equals("/api/admin/monitor-settings") && method.equals("PUT")) {
                try { monitor.update(body(e, 4096)); }
                catch (IllegalArgumentException ex) { throw new Problem(400, ex.getMessage()); }
                reply(e, 200, monitor.view(60)); return;
            }
            throw new Problem(404, "监控接口不存在");
        }
        if (path.equals("/api/session") && method.equals("POST")) {
            JsonObject body = body(e, 4096);
            String owner;
            if (authMode.equals("development")) {
                String device = string(body, "deviceId");
                require(device.matches("[A-Za-z0-9_-]{16,80}"), "开发设备标识无效");
                owner = "dev:" + device;
            } else {
                String code = string(body, "code");
                require(!code.isBlank() && code.length() < 256, "登录凭证无效");
                URI uri = URI.create("https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appId)
                    + "&secret=" + encode(secret) + "&js_code=" + encode(code) + "&grant_type=authorization_code");
                var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                JsonObject login = JSON.fromJson(response.body(), JsonObject.class);
                if (response.statusCode() != 200 || login == null || !login.has("openid"))
                    throw new Problem(401, "微信登录失败，请重试");
                owner = "wx:" + login.get("openid").getAsString();
            }
            String token = UUID.randomUUID() + "-" + UUID.randomUUID();
            sessions.put(token, new Session(owner, System.currentTimeMillis() + 2 * 60 * 60 * 1000L));
            reply(e, 200, Map.of("token", token, "expiresSeconds", 7200));
            return;
        }
        Session session = authenticate(e);
        if (path.equals("/api/files") && method.equals("POST")) {
            synchronized (files) {
                long count = files.values().stream().filter(f -> f.owner.equals(session.owner)).count();
                if (count >= 30) throw new Problem(429, "暂存文件过多，请删除旧文件后重试");
                String name = query(e.getRequestURI()).getOrDefault("name", "file");
                require(name.length() <= 160 && !name.contains("/") && !name.contains("\\") && !name.contains("\n")
                    && !name.contains("\r"), "文件名无效");
                String suffix = extension(name);
                require(suffix.equals("pdf") || ImageCodec.INPUTS.contains(suffix), "支持 BMP/DIB、JPEG、GIF、TIFF、PNG、HEIC 和 PDF 文件");
                byte[] content = readLimited(e.getRequestBody(), MAX_BYTES);
                require(content.length > 0, "文件不能为空");
                String id = UUID.randomUUID().toString();
                Path file = data.resolve(id + "." + suffix);
                Files.write(file, content, StandardOpenOption.CREATE_NEW);
                files.put(id, new StoredFile(id, session.owner, file, name, content.length, System.currentTimeMillis()));
                reply(e, 201, Map.of("id", id, "name", name, "size", content.length));
            }
            return;
        }
        if (path.equals("/api/jobs") && method.equals("POST")) {
            Request request;
            try { request = JSON.fromJson(new String(readLimited(e.getRequestBody(), 8192), StandardCharsets.UTF_8), Request.class); }
            catch (RuntimeException ex) { throw new Problem(400, "任务参数无效"); }
            validate(request);
            var inputs = request.fileIds.stream().map(id -> ownedFile(id, session.owner)).toList();
            if (request.operation.equals("idPhoto"))
                require(Set.of("jpg", "jpeg", "png", "heic").contains(extension(inputs.get(0).name)), "证件照请选择 JPG、PNG 或 HEIC 照片");
            long ownerJobs = jobs.values().stream().filter(j -> j.owner.equals(session.owner)
                && Set.of("queued", "running").contains(j.state)).count();
            if (ownerJobs >= 2) throw new Problem(429, "请等待当前任务完成");
            Job job = new Job(session.owner);
            jobs.put(job.id, job);
            try { worker.execute(() -> convert(job, request, inputs)); }
            catch (RejectedExecutionException ex) {
                jobs.remove(job.id);
                throw new Problem(429, "转换任务较多，请稍后重试");
            }
            reply(e, 202, jobView(job));
            return;
        }
        if (path.startsWith("/api/jobs/") && method.equals("GET")) {
            Job job = jobs.get(path.substring("/api/jobs/".length()));
            if (job == null || !job.owner.equals(session.owner)) throw new Problem(404, "任务不存在或已过期");
            reply(e, 200, jobView(job));
            return;
        }
        if (path.startsWith("/api/files/")) {
            StoredFile file = ownedFile(path.substring("/api/files/".length()), session.owner);
            if (method.equals("GET")) {
                String suffix = extension(file.name);
                e.getResponseHeaders().set("Content-Type", switch (suffix) {
                    case "pdf" -> "application/pdf";
                    case "png" -> "image/png";
                    case "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg";
                    case "bmp" -> "image/bmp";
                    case "gif" -> "image/gif";
                    case "tif", "tiff" -> "image/tiff";
                    case "heic" -> "image/heic";
                    case "zip" -> "application/zip";
                    default -> "application/octet-stream";
                });
                e.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + encode(file.name));
                e.getResponseHeaders().set("Cache-Control", "no-store");
                e.sendResponseHeaders(200, Files.size(file.path));
                try (var output = e.getResponseBody()) { Files.copy(file.path, output); }
                return;
            }
            if (method.equals("DELETE")) {
                boolean busy = jobs.values().stream().anyMatch(j -> j.owner.equals(session.owner)
                    && Set.of("queued", "running").contains(j.state));
                if (busy) throw new Problem(409, "转换中暂时不能删除文件");
                Files.deleteIfExists(file.path);
                files.remove(file.id);
                reply(e, 200, Map.of("deleted", true));
                return;
            }
        }
        throw new Problem(404, "接口不存在");
    }

    static void serveWeb(HttpExchange e, String path, String method) throws IOException {
        String resource = switch (path) {
            case "/", "/index.html" -> "web/index.html";
            case "/assets/style.css" -> "web/style.css";
            case "/assets/app.js" -> "web/app.js";
            case "/idphoto", "/idphoto/" -> "web/idphoto.html";
            case "/assets/idphoto.js" -> "web/idphoto.js";
            case "/assets/idphoto.css" -> "web/idphoto.css";
            case "/monitor", "/monitor/" -> "web/monitor.html";
            case "/assets/monitor.js" -> "web/monitor.js";
            case "/assets/monitor.css" -> "web/monitor.css";
            case "/favicon.svg" -> "web/favicon.svg";
            default -> null;
        };
        if (resource == null || !Set.of("GET", "HEAD").contains(method))
            throw new Problem(404, "页面不存在");
        try (InputStream input = Server.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new Problem(404, "网页资源未构建，请重新运行 build.cmd");
            byte[] content = input.readAllBytes();
            String type = resource.endsWith(".html") ? "text/html; charset=utf-8"
                : resource.endsWith(".css") ? "text/css; charset=utf-8"
                : resource.endsWith(".js") ? "text/javascript; charset=utf-8" : "image/svg+xml";
            e.getResponseHeaders().set("Content-Type", type);
            e.getResponseHeaders().set("Cache-Control", "no-cache");
            e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            e.getResponseHeaders().set("Referrer-Policy", "same-origin");
            e.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            if (method.equals("HEAD")) { e.sendResponseHeaders(200, -1); return; }
            e.sendResponseHeaders(200, content.length);
            try (OutputStream output = e.getResponseBody()) { output.write(content); }
        }
    }

    void convert(Job job, Request request, List<StoredFile> inputs) {
        job.state = "running"; job.message = "正在转换"; job.progress = 10;
        String suffix = switch (request.operation) {
            case "imagesToPdf" -> "pdf";
            case "pdfToImages" -> "zip";
            case "compress" -> "jpg";
            default -> request.format;
        };
        String id = UUID.randomUUID().toString();
        Path output = data.resolve(id + "." + suffix);
        try {
            switch (request.operation) {
                case "idPhoto" -> { job.message = "正在本地抠图和裁剪"; IdPhoto.generate(inputs.get(0).path, output, request); }
                case "compress", "convertImage" -> {
                    BufferedImage image = readImage(inputs.get(0).path);
                    if (request.operation.equals("compress")) image = resize(image, request.maxWidth);
                    ImageCodec.write(image, output, suffix, request.quality, request.bmpBits);
                    image.flush();
                }
                case "imagesToPdf" -> {
                    try (PDDocument document = new PDDocument()) {
                        for (int index = 0; index < inputs.size(); index++) {
                            BufferedImage image = readImage(inputs.get(index).path);
                            var page = new PDPage(PDRectangle.A4);
                            document.addPage(page);
                            float scale = Math.min((PDRectangle.A4.getWidth() - 40) / image.getWidth(),
                                (PDRectangle.A4.getHeight() - 40) / image.getHeight());
                            float width = image.getWidth() * scale, height = image.getHeight() * scale;
                            try (var canvas = new PDPageContentStream(document, page)) {
                                canvas.drawImage(LosslessFactory.createFromImage(document, image),
                                    (PDRectangle.A4.getWidth() - width) / 2, (PDRectangle.A4.getHeight() - height) / 2, width, height);
                            }
                            image.flush();
                            job.progress = 10 + (index + 1) * 70 / inputs.size();
                        }
                        document.save(output.toFile());
                    }
                }
                case "pdfToImages" -> {
                    try (PDDocument document = Loader.loadPDF(inputs.get(0).path.toFile());
                         var archive = new ZipOutputStream(Files.newOutputStream(output))) {
                        require(!document.isEncrypted(), "暂不支持加密 PDF");
                        int pages = document.getNumberOfPages();
                        require(pages > 0 && pages <= 20, "PDF 转图片最多支持 20 页");
                        PDFRenderer renderer = new PDFRenderer(document);
                        renderer.setSubsamplingAllowed(true);
                        for (int index = 0; index < pages; index++) {
                            PDRectangle rect = document.getPage(index).getCropBox();
                            double pixelWidth = Math.ceil(rect.getWidth() * request.dpi / 72.0);
                            double pixelHeight = Math.ceil(rect.getHeight() * request.dpi / 72.0);
                            require(pixelWidth > 0 && pixelHeight > 0 && pixelWidth <= 16000 && pixelHeight <= 16000
                                && pixelWidth * pixelHeight <= MAX_PIXELS, "PDF 页面尺寸过大，请降低清晰度");
                            BufferedImage image = renderer.renderImageWithDPI(index, request.dpi, ImageType.RGB);
                            archive.putNextEntry(new ZipEntry(String.format("page-%03d.%s", index + 1, request.format)));
                            var pageBytes = new ByteArrayOutputStream();
                            writeImage(image, pageBytes, request.format, request.quality);
                            pageBytes.writeTo(archive);
                            archive.closeEntry(); image.flush();
                            job.progress = 10 + (index + 1) * 70 / pages;
                        }
                    }
                }
                default -> throw new Problem(400, "转换类型无效");
            }
            String name = switch (request.operation) {
                case "compress" -> "compressed.jpg";
                case "imagesToPdf" -> "images.pdf";
                case "pdfToImages" -> "pdf-pages.zip";
                case "idPhoto" -> "id-photo-" + request.photoWidthMm + "x" + request.photoHeightMm + "." + suffix;
                default -> "converted." + suffix;
            };
            files.put(id, new StoredFile(id, job.owner, output, name, Files.size(output), System.currentTimeMillis()));
            job.resultId = id; job.progress = 100; job.message = "转换完成"; job.state = "done";
        } catch (Exception exception) {
            try { Files.deleteIfExists(output); } catch (IOException ignored) {}
            job.message = exception instanceof Problem ? exception.getMessage() : "文件无法转换，可能已损坏、加密或格式不受支持";
            job.state = "failed";
        }
    }
    static void validate(Request r) {
        require(r != null && r.operation != null && Set.of("compress", "convertImage", "imagesToPdf", "pdfToImages", "idPhoto").contains(r.operation), "请选择有效转换类型");
        require(r.fileIds != null && !r.fileIds.isEmpty() && r.fileIds.size() <= 9
            && r.fileIds.stream().allMatch(Objects::nonNull), "请选择 1 至 9 个文件");
        require(r.operation.equals("imagesToPdf") || r.fileIds.size() == 1, "此功能每次处理一个文件");
        require(r.quality >= 30 && r.quality <= 95, "压缩质量应在 30 至 95 之间");
        require(r.maxWidth >= 320 && r.maxWidth <= 4000, "图片最大宽度应在 320 至 4000 之间");
        require(r.dpi >= 72 && r.dpi <= 180, "PDF 清晰度应在 72 至 180 DPI 之间");
        require(Set.of(1, 4, 8, 24).contains(r.bmpBits), "位图位深仅支持 1、4、8、24 位");
        if (r.operation.equals("idPhoto")) {
            require(r.photoWidthMm >= 20 && r.photoWidthMm <= 100 && r.photoHeightMm >= 20 && r.photoHeightMm <= 120, "证件照宽 20–100mm、高 20–120mm");
            require(r.photoDpi >= 150 && r.photoDpi <= 600, "证件照清晰度应在 150–600 DPI 之间");
            require(r.photoBackground != null && Set.of("white", "blue", "red").contains(r.photoBackground), "请选择白、蓝或红色背景");
            require(Double.isFinite(r.photoZoom) && r.photoZoom >= 0.8 && r.photoZoom <= 1.5, "画面缩放应在 80–150% 之间");
            require(Double.isFinite(r.photoOffsetX) && Double.isFinite(r.photoOffsetY) && Math.abs(r.photoOffsetX) <= 15 && Math.abs(r.photoOffsetY) <= 20, "人物位置调整超出范围");
        }
        if (Set.of("convertImage", "pdfToImages", "idPhoto").contains(r.operation)) {
            require(r.format != null, "请选择输出格式");
            r.format = ImageCodec.canonical(r.format);
            require((r.operation.equals("convertImage") ? ImageCodec.OUTPUTS : Set.of("jpg", "png")).contains(r.format), "输出格式不受支持");
        }
    }
    @Override public void close() { worker.shutdownNow(); cleaner.shutdownNow(); monitor.close(); }
    static boolean monitorAuthorized(String mode, InetAddress remote, String token, String authorization) {
        if (token.isBlank()) return mode.equals("development") && remote != null && remote.isLoopbackAddress();
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        return java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }
    static BufferedImage readImage(Path path) throws IOException {
        return ImageCodec.read(path);
    }
    static BufferedImage resize(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;
        int height = Math.max(1, (int) Math.round(source.getHeight() * (double) maxWidth / source.getWidth()));
        var target = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, maxWidth, height, null); graphics.dispose(); source.flush();
        return target;
    }
    static void writeImage(BufferedImage image, Path path, String format, int quality) throws IOException {
        try (var stream = Files.newOutputStream(path)) { writeImage(image, stream, format, quality); }
    }
    static void writeImage(BufferedImage image, OutputStream stream, String format, int quality) throws IOException {
        if (format.equals("png")) {
            if (!ImageIO.write(image, "png", stream)) throw new IOException("PNG encoder unavailable");
            return;
        }
        var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics(); graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); graphics.drawImage(image, 0, 0, null); graphics.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(stream)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality / 100f);
            writer.write(null, new IIOImage(rgb, null, null), parameters); output.flush();
        } finally { writer.dispose(); rgb.flush(); }
    }

    Map<String, Object> jobView(Job job) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", job.id); view.put("state", job.state); view.put("progress", job.progress);
        view.put("message", job.message);
        if (job.resultId != null) {
            StoredFile result = files.get(job.resultId);
            if (result != null) view.put("result", Map.of("id", result.id, "name", result.name, "size", result.size));
        }
        return view;
    }
    Session authenticate(HttpExchange e) {
        String authorization = e.getRequestHeaders().getFirst("Authorization");
        Session session = authorization != null && authorization.startsWith("Bearer ") ? sessions.get(authorization.substring(7)) : null;
        if (session == null || session.expires < System.currentTimeMillis()) throw new Problem(401, "登录已过期，请重新打开小程序");
        return session;
    }
    StoredFile ownedFile(String id, String owner) {
        StoredFile file = files.get(id);
        if (file == null || !file.owner.equals(owner) || file.created + TTL < System.currentTimeMillis() || !Files.exists(file.path))
            throw new Problem(404, "文件不存在或已过期");
        return file;
    }
    void cleanup() {
        try {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> entry.getValue().expires < now);
            jobs.entrySet().removeIf(entry -> entry.getValue().created + TTL < now
                && !Set.of("queued", "running").contains(entry.getValue().state));
            files.entrySet().removeIf(entry -> entry.getValue().created + TTL < now);
            try (var paths = Files.list(data)) {
                for (Path path : paths.toList())
                    if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() + TTL < now)
                        Files.deleteIfExists(path);
            }
        } catch (Exception e) { System.err.println("Temporary file cleanup failed"); }
    }
    static JsonObject body(HttpExchange e, int max) throws IOException {
        try {
            JsonObject value = JSON.fromJson(new String(readLimited(e.getRequestBody(), max), StandardCharsets.UTF_8), JsonObject.class);
            require(value != null, "请求内容不能为空"); return value;
        } catch (RuntimeException exception) { throw new Problem(400, "请求参数无效"); }
    }
    static String string(JsonObject value, String name) { return value.has(name) ? value.get(name).getAsString() : ""; }
    static byte[] readLimited(InputStream input, long max) throws IOException {
        var bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; long total = 0;
        for (int length; (length = input.read(buffer)) != -1;) {
            total += length; if (total > max) throw new Problem(413, "文件或请求过大，单文件上限 20MB");
            bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
    }
    static void reply(HttpExchange e, int status, Object body) throws IOException {
        byte[] bytes = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.sendResponseHeaders(status, bytes.length);
        try (var stream = e.getResponseBody()) { stream.write(bytes); }
    }
    static Map<String, String> query(URI uri) {
        var map = new HashMap<String, String>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
    static String extension(String name) { return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String env(String name, String fallback) { String value = System.getenv(name); return value == null ? fallback : value; }
    static void require(boolean condition, String message) { if (!condition) throw new Problem(400, message); }
}

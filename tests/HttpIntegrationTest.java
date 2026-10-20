package formatfactory;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

public final class HttpIntegrationTest {
    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static String base;
    static HttpResponse<byte[]> request(String method, String path, String token, byte[] body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    static byte[] json(Object value) { return Server.JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    static JsonObject parse(HttpResponse<byte[]> response) { return Server.JSON.fromJson(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static String session(String device) throws Exception {
        var response = request("POST", "/api/session", null, json(Map.of("deviceId", device)));
        check(response.statusCode() == 200, "session creation failed"); return parse(response).get("token").getAsString();
    }
    public static void main(String[] args) throws Exception {
        var app = new Server(Path.of("build", "http-test-data"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", app::handle); server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var home = request("GET", "/", null, null);
            check(home.statusCode() == 200 && home.headers().firstValue("content-type").orElse("").contains("text/html"), "browser homepage missing");
            check(request("GET", "/assets/app.js", null, null).statusCode() == 200, "browser script missing");
            check(request("GET", "/assets/style.css", null, null).statusCode() == 200, "browser stylesheet missing");
            check(request("GET", "/idphoto", null, null).statusCode() == 200, "ID photo page missing");
            check(request("GET", "/assets/idphoto.js", null, null).statusCode() == 200, "ID photo script missing");
            check(request("GET", "/assets/idphoto.css", null, null).statusCode() == 200, "ID photo stylesheet missing");
            check(request("GET", "/assets/Server.java", null, null).statusCode() == 404, "static asset allowlist missing");
            check(request("GET", "/api/health", null, null).statusCode() == 200, "health failed");
            check(request("GET", "/monitor", null, null).statusCode() == 200, "monitor page missing");
            check(request("HEAD", "/monitor/", null, null).statusCode() == 200, "monitor HEAD failed");
            check(request("GET", "/assets/monitor.js", null, null).statusCode() == 200, "monitor script missing");
            check(request("GET", "/assets/monitor.css", null, null).statusCode() == 200, "monitor stylesheet missing");
            var metrics = request("GET", "/api/admin/metrics?limit=1", null, null);
            check(metrics.statusCode() == 200 && parse(metrics).has("latest"), "local development metrics unavailable");
            for (String limit : List.of("0", "3601", "NaN"))
                check(request("GET", "/api/admin/metrics?limit=" + limit, null, null).statusCode() == 400, "invalid metric limit accepted");
            check(request("PUT", "/api/admin/monitor-settings", null, json(Map.of("cpuPercent", 0))).statusCode() == 400, "missing/invalid monitor settings accepted");
            var monitorSettings = Map.of("cpuPercent", 80, "memoryPercent", 85, "heapPercent", 85, "intervalSeconds", 5, "consecutiveSamples", 3);
            var saved = request("PUT", "/api/admin/monitor-settings", null, json(monitorSettings));
            check(saved.statusCode() == 200 && parse(saved).getAsJsonObject("settings").get("intervalSeconds").getAsInt() == 5, "monitor settings update failed");
            check(Files.isRegularFile(app.monitor.config.directory().resolve("settings.json")), "monitor settings file missing");
            check(request("GET", "/api/jobs/unknown", null, null).statusCode() == 401, "unauthorized request accepted");
            String alice = session("integration_alice_12345"), bob = session("integration_bob_123456");
            byte[] original = Files.readAllBytes(Path.of("build", "test-data", "原图.png"));
            var upload = request("POST", "/api/files?name=" + Server.encode("测试 图片.png"), alice, original);
            check(upload.statusCode() == 201, "binary upload failed");
            String file = parse(upload).get("id").getAsString();
            check(request("GET", "/api/files/" + file, bob, null).statusCode() == 404, "cross-user download accepted");
            check(Arrays.equals(original, request("GET", "/api/files/" + file, alice, null).body()), "uploaded bytes changed");
            var submission = request("POST", "/api/jobs", alice, json(Map.of("operation", "compress", "fileIds", List.of(file))));
            check(submission.statusCode() == 202, "job submission failed");
            String id = parse(submission).get("id").getAsString();
            JsonObject status = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                status = parse(request("GET", "/api/jobs/" + id, alice, null));
                String state = status.get("state").getAsString();
                if (Set.of("done", "failed").contains(state)) break;
                Thread.sleep(100);
            }
            check(status != null && status.get("state").getAsString().equals("done"), "async job failed or timed out");
            String result = status.getAsJsonObject("result").get("id").getAsString();
            var download = request("GET", "/api/files/" + result, alice, null);
            check(download.statusCode() == 200 && ImageIO.read(new ByteArrayInputStream(download.body())).getWidth() == 2400, "result download invalid");
            check(request("DELETE", "/api/files/" + result, alice, null).statusCode() == 200, "delete failed");
            check(request("GET", "/api/files/" + result, alice, null).statusCode() == 404, "deleted result remains accessible");
            var bitmapJob = request("POST", "/api/jobs", alice, json(Map.of("operation", "convertImage", "fileIds", List.of(file), "format", "bmp", "bmpBits", 4)));
            check(bitmapJob.statusCode() == 202, "extended format job rejected");
            String bitmapId = parse(bitmapJob).get("id").getAsString();
            deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                status = parse(request("GET", "/api/jobs/" + bitmapId, alice, null));
                if (Set.of("done", "failed").contains(status.get("state").getAsString())) break;
                Thread.sleep(100);
            }
            check(status.get("state").getAsString().equals("done"), "BMP HTTP conversion failed");
            var bitmap = request("GET", "/api/files/" + status.getAsJsonObject("result").get("id").getAsString(), alice, null);
            check(bitmap.headers().firstValue("content-type").orElse("").equals("image/bmp"), "BMP MIME type");
            check(java.nio.ByteBuffer.wrap(bitmap.body()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(28) == 4, "HTTP bmpBits setting ignored");
            for (String extension : ImageCodec.INPUTS) {
                var uploadedFormat = request("POST", "/api/files?name=test." + extension, alice, original);
                check(uploadedFormat.statusCode() == 201, "Input extension rejected: " + extension);
                request("DELETE", "/api/files/" + parse(uploadedFormat).get("id").getAsString(), alice, null);
            }
            check(request("POST", "/api/jobs", alice, json(Map.of("operation", "invalid", "fileIds", List.of(file)))).statusCode() == 400, "invalid operation accepted");
            check(request("POST", "/api/files?name=wrong.exe", alice, original).statusCode() == 400, "unsupported extension accepted");
            var photo = new LinkedHashMap<String, Object>();
            photo.put("operation", "idPhoto"); photo.put("fileIds", List.of(file)); photo.put("format", "png");
            photo.put("photoWidthMm", 200);
            check(request("POST", "/api/jobs", alice, json(photo)).statusCode() == 400, "invalid ID photo dimensions accepted");
            photo.put("photoWidthMm", 25); photo.put("photoBackground", "green");
            check(request("POST", "/api/jobs", alice, json(photo)).statusCode() == 400, "invalid ID photo background accepted");
            photo.put("photoBackground", "blue"); photo.put("photoZoom", 2);
            check(request("POST", "/api/jobs", alice, json(photo)).statusCode() == 400, "invalid ID photo zoom accepted");
            photo.put("photoZoom", 1);
            check(request("POST", "/api/jobs", bob, json(photo)).statusCode() == 404, "cross-user portrait accepted");
            var wrongPhoto = request("POST", "/api/files?name=wrong.gif", alice, original);
            String wrongPhotoId = parse(wrongPhoto).get("id").getAsString();
            photo.put("fileIds", List.of(wrongPhotoId));
            check(request("POST", "/api/jobs", alice, json(photo)).statusCode() == 400, "unsupported portrait format accepted");
            request("DELETE", "/api/files/" + wrongPhotoId, alice, null);
            System.out.println("PASS: HTTP sessions, binary upload, async conversion, polling, download, deletion, access control, invalid requests");
        } finally { server.stop(0); app.close(); }
    }
}

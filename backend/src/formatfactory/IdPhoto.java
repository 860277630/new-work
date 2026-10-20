package formatfactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Runs local model inference in a bounded child JVM; no network API is used. */
final class IdPhoto {
    static Path models() { return Path.of(Server.env("IDPHOTO_MODELS_DIR", ".tools/idphoto")).toAbsolutePath().normalize(); }
    static boolean available() {
        return Files.isRegularFile(models().resolve("modnet.onnx")) && Files.isRegularFile(models().resolve("face-rfb-320.onnx"));
    }
    static void generate(Path input, Path output, Server.Request request) throws Exception {
        Server.require(available(), "请先运行 setup-idphoto.cmd 配置本地证件照模型");
        Path log = Files.createTempFile(output.getParent(), "idphoto-", ".log");
        Process process = null;
        try {
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            process = new ProcessBuilder(executable, "-Xmx384m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                "formatfactory.IdPhotoWorker", input.toAbsolutePath().toString(), output.toAbsolutePath().toString(), models().toString(), Server.JSON.toJson(request))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(75, TimeUnit.SECONDS)) throw new Server.Problem(400, "证件照处理超时，请缩小照片后重试");
            if (process.exitValue() != 0) {
                String details = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                String message = details.lines().filter(line -> line.startsWith("IDPHOTO_ERROR:")).map(line -> line.substring(14)).findFirst()
                    .orElse("本地 AI 处理失败，请检查模型、运行库和照片");
                throw new Server.Problem(400, message);
            }
            Server.require(Files.isRegularFile(output) && Files.size(output) > 0, "证件照未能生成，请重试");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            Files.deleteIfExists(log);
        }
    }
}

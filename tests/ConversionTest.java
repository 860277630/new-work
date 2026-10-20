package formatfactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

public final class ConversionTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static Server.Job run(Server server, String operation, List<String> ids, String format, String owner) {
        var request = new Server.Request(); request.operation = operation; request.fileIds = ids; request.format = format;
        Server.validate(request);
        var job = new Server.Job(owner);
        server.convert(job, request, ids.stream().map(id -> server.ownedFile(id, owner)).toList());
        return job;
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "test-data").toAbsolutePath(); Files.createDirectories(root);
        var server = new Server(root);
        try {
            var image = new BufferedImage(3200, 1000, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(20, 20, Color.RED.getRGB());
            var brush = image.createGraphics(); brush.setColor(Color.RED); brush.fillRect(1000, 200, 700, 500); brush.dispose();
            Path source = root.resolve("原图.png"); ImageIO.write(image, "png", source.toFile());
            String originalHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            server.files.put("image", new Server.StoredFile("image", "alice", source, "原图.png", Files.size(source), System.currentTimeMillis()));
            var compress = run(server, "compress", List.of("image"), "jpg", "alice");
            check(compress.state.equals("done"), "compression failed: " + compress.message);
            var compressed = ImageIO.read(server.files.get(compress.resultId).path().toFile());
            check(compressed.getWidth() == 2400 && compressed.getHeight() == 750, "resize incorrect");
            check(new Color(compressed.getRGB(100, 100)).getRed() > 240, "transparent pixels must become white");
            var convert = run(server, "convertImage", List.of("image"), "png", "alice");
            check(convert.state.equals("done"), "PNG conversion failed");
            var png = ImageIO.read(server.files.get(convert.resultId).path().toFile());
            check((png.getRGB(100, 100) >>> 24) == 0, "PNG transparency lost");
            check(run(server, "convertImage", List.of(compress.resultId), "png", "alice").state.equals("done"), "JPG to PNG failed");
            var pdf = run(server, "imagesToPdf", List.of("image", "image"), null, "alice");
            check(pdf.state.equals("done"), "PDF creation failed: " + pdf.message);
            try (PDDocument document = Loader.loadPDF(server.files.get(pdf.resultId).path().toFile())) {
                check(document.getNumberOfPages() == 2, "PDF page order/count incorrect");
            }
            var render = run(server, "pdfToImages", List.of(pdf.resultId), "png", "alice");
            check(render.state.equals("done"), "PDF rendering failed: " + render.message);
            try (var zip = new ZipFile(server.files.get(render.resultId).path().toFile())) {
                check(zip.size() == 2, "ZIP should have two pages");
                var rendered = ImageIO.read(zip.getInputStream(zip.getEntry("page-001.png")));
                check(rendered != null, "rendered PNG invalid");
                var center = new Color(rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2));
                check(center.getRed() > 200 && center.getGreen() < 80, "rendered page lost image content");
            }
            check(run(server, "pdfToImages", List.of(pdf.resultId), "jpg", "alice").state.equals("done"), "PDF to JPG failed");
            Path tooMany = root.resolve("21pages.pdf");
            try (var document = new PDDocument()) { for (int i = 0; i < 21; i++) document.addPage(new PDPage(PDRectangle.A4)); document.save(tooMany.toFile()); }
            server.files.put("tooMany", new Server.StoredFile("tooMany", "alice", tooMany, "21pages.pdf", Files.size(tooMany), System.currentTimeMillis()));
            check(run(server, "pdfToImages", List.of("tooMany"), "jpg", "alice").state.equals("failed"), "page limit missing");
            Path corrupt = root.resolve("corrupt.png"); Files.writeString(corrupt, "not an image");
            server.files.put("corrupt", new Server.StoredFile("corrupt", "alice", corrupt, "corrupt.png", 12, System.currentTimeMillis()));
            check(run(server, "compress", List.of("corrupt"), "jpg", "alice").state.equals("failed"), "corrupt image accepted");
            try { server.ownedFile("image", "bob"); throw new AssertionError("cross-user access permitted"); }
            catch (Server.Problem expected) { check(expected.status == 404, "ownership rejection status"); }
            check(originalHash.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))), "source was changed");
            System.out.println("PASS: compress, PNG transparency, image-to-PDF, PDF-to-PNG ZIP, page limits, corrupt input, ownership, source preservation");
        } finally { server.close(); }
    }
}

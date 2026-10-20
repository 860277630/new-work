package formatfactory;

import javax.imageio.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class ImageFormatsTest {
    record Target(String format, int bits) {
        String label() { return format + ((format.equals("bmp") || format.equals("dib")) ? bits : ""); }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void verify(Path path, Target target) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        switch (target.format) {
            case "bmp", "dib" -> {
                int base = target.format.equals("bmp") ? 14 : 0;
                if (base == 14) check(bytes[0] == 'B' && bytes[1] == 'M', "BMP signature");
                ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                check(header.getInt(base) == 40, "DIB header size");
                check(header.getShort(base + 14) == target.bits, "Actual bitmap depth differs from selection: " + path);
                int offset = base + 40 + (target.bits == 24 ? 0 : (1 << target.bits) * 4);
                check(bytes.length == offset + ((64 * target.bits + 31) / 32 * 4) * 48, "BMP row padding/data size");
            }
            case "jpg" -> check((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216, "JPEG signature");
            case "png" -> check(HexFormat.of().formatHex(Arrays.copyOf(bytes, 8)).equals("89504e470d0a1a0a"), "PNG signature");
            case "gif" -> check(new String(bytes, 0, 3).equals("GIF"), "GIF signature");
            case "tiff" -> check(new String(bytes, 0, 2).equals("MM") || new String(bytes, 0, 2).equals("II"), "TIFF signature");
            case "heic" -> {
                check(new String(bytes, 4, 4).equals("ftyp"), "HEIC ISO-BMFF signature");
                String brands = new String(bytes, 8, Math.min(bytes.length - 8, 32), java.nio.charset.StandardCharsets.ISO_8859_1);
                check(brands.contains("heic") || brands.contains("heix"), "HEIC must contain HEVC brand, not AVIF/JPEG renamed");
            }
        }
        BufferedImage decoded = ImageCodec.read(path);
        check(decoded.getWidth() == 64 && decoded.getHeight() == 48, "Dimensions lost: " + path); decoded.flush();
    }
    static void firstPage(Path root, String format) throws Exception {
        Path path = root.resolve("two-pages." + format);
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output); writer.prepareWriteSequence(null);
            for (Color color : List.of(Color.RED, Color.BLUE)) {
                BufferedImage page = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
                var g = page.createGraphics(); g.setColor(color); g.fillRect(0, 0, 64, 48); g.dispose();
                writer.writeToSequence(new IIOImage(page, null, null), writer.getDefaultWriteParam()); page.flush();
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        BufferedImage first = ImageCodec.read(path);
        Color pixel = new Color(first.getRGB(32, 24));
        check(pixel.getRed() > 240 && pixel.getBlue() < 20, "Must select first GIF frame / TIFF page: " + format); first.flush();
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "format-test").toAbsolutePath(); Files.createDirectories(root);
        List<Target> targets = new ArrayList<>();
        for (String format : List.of("jpg", "png", "gif", "tiff")) targets.add(new Target(format, 24));
        for (String format : List.of("bmp", "dib")) for (int bits : List.of(1, 4, 8, 24)) targets.add(new Target(format, bits));
        boolean heic = Files.isRegularFile(Path.of(ImageCodec.heifTool("heif-enc"))) || Arrays.asList(args).contains("--heic");
        if (heic) targets.add(new Target("heic", 24));
        BufferedImage source = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        var brush = source.createGraphics(); brush.setColor(Color.RED); brush.fillRect(8, 8, 40, 32); brush.dispose();
        Map<Target, Path> inputs = new LinkedHashMap<>();
        for (Target target : targets) {
            Path path = root.resolve("source-" + target.label() + "." + target.format);
            ImageCodec.write(source, path, target.format, 90, target.bits); verify(path, target); inputs.put(target, path);
            BufferedImage image = ImageCodec.read(path);
            if (target.bits != 1) {
                Color center = new Color(image.getRGB(32, 24));
                check(center.getRed() > 180 && center.getGreen() < 80 && center.getBlue() < 80, "Image content/colors lost: " + target.label());
            }
            if (Set.of("png", "tiff").contains(target.format)) check(image.getRGB(0,0) >>> 24 == 0, "Transparent channel lost: " + target.format);
            image.flush();
        }
        int count = 0;
        for (var input : inputs.entrySet()) {
            BufferedImage image = ImageCodec.read(input.getValue());
            try {
                for (Target target : targets) {
                    Path output = root.resolve(input.getKey().label() + "-to-" + target.label() + "." + target.format);
                    ImageCodec.write(image, output, target.format, 90, target.bits); verify(output, target); count++;
                }
            } finally { image.flush(); }
        }
        for (String alias : List.of("jpeg", "jpe", "jfif", "tif")) {
            Path original = root.resolve("source-" + (alias.equals("tif") ? "tiff" : "jpg") + "." + (alias.equals("tif") ? "tiff" : "jpg"));
            Path aliasFile = root.resolve("alias." + alias); Files.copy(original, aliasFile, StandardCopyOption.REPLACE_EXISTING);
            check(ImageCodec.read(aliasFile).getWidth() == 64, "Input alias rejected: " + alias);
            var request = new Server.Request(); request.operation = "convertImage"; request.fileIds = List.of("test"); request.format = alias;
            Server.validate(request); check(request.format.equals(alias.equals("tif") ? "tiff" : "jpg"), "Output alias canonicalization");
        }
        firstPage(root, "gif"); firstPage(root, "tiff");
        for (Target target : targets) if (target.format.equals("bmp") || target.format.equals("dib")) {
            BufferedImage flat = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            Path file = root.resolve("solid-" + target.label() + "." + target.format);
            ImageCodec.write(flat, file, target.format, 90, target.bits); verify(file, target); flat.flush();
        }
        var bad = new Server.Request(); bad.operation = "convertImage"; bad.fileIds = List.of("test"); bad.format = "bmp"; bad.bmpBits = 16;
        try { Server.validate(bad); throw new AssertionError("16-color must use 4-bit; unsupported 16-bit accepted"); } catch (Server.Problem expected) { }
        source.flush();
        System.out.println("PASS: " + count + " format/depth conversions, real signatures/bitmap depths, aliases, solid bitmaps, first GIF frame/TIFF page" + (heic ? ", HEIC encode and decode" : " (HEIC test: run setup-heic.cmd first or pass --heic on Linux)"));
    }
}

package formatfactory;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** First frame/page only. BMP bit depth is written explicitly, including raw DIB. */
final class ImageCodec {
    static final Set<String> INPUTS = Set.of("jpg", "jpeg", "jpe", "jfif", "png", "bmp", "dib", "gif", "tif", "tiff", "heic");
    static final Set<String> OUTPUTS = Set.of("jpg", "png", "bmp", "dib", "gif", "tiff", "heic");

    static String canonical(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpe", "jfif" -> "jpg";
            case "tif" -> "tiff";
            default -> format.toLowerCase(Locale.ROOT);
        };
    }
    static BufferedImage read(Path path) throws IOException {
        String suffix = Server.extension(path.toString());
        if (suffix.equals("heic")) return readHeic(path);
        if (suffix.equals("dib")) {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M')
                return readNative(ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)));
            byte[] header = dibFileHeader(bytes);
            return readNative(ImageIO.createImageInputStream(new SequenceInputStream(
                new ByteArrayInputStream(header), new ByteArrayInputStream(bytes))));
        }
        return readNative(ImageIO.createImageInputStream(path.toFile()));
    }
    static BufferedImage readNative(ImageInputStream stream) throws IOException {
        try (ImageInputStream input = stream) {
            Server.require(input != null, "图片内容无效");
            var readers = ImageIO.getImageReaders(input);
            Server.require(readers.hasNext(), "图片内容无效");
            ImageReader reader = readers.next();
            try {
                Server.require(Set.of("JPEG", "PNG", "BMP", "GIF", "TIF", "TIFF").contains(reader.getFormatName().toUpperCase(Locale.ROOT)), "图片格式不受支持");
                reader.setInput(input, true, true);
                dimensions(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
    static void dimensions(long width, long height) {
        Server.require(width > 0 && height > 0 && width <= 16000 && height <= 16000
            && width * height <= Server.MAX_PIXELS, "图片尺寸过大，最多支持 2400 万像素");
    }
    static byte[] dibFileHeader(byte[] dib) {
        Server.require(dib.length >= 12, "DIB 图片内容无效");
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        int header = b.getInt(0), bits, paletteSize, masks = 0;
        long colors;
        if (header == 12) {
            bits = Short.toUnsignedInt(b.getShort(10)); colors = bits <= 8 ? 1L << bits : 0; paletteSize = 3;
        } else {
            Server.require(Set.of(40, 52, 56, 108, 124).contains(header) && dib.length >= header, "DIB 头信息无效");
            bits = Short.toUnsignedInt(b.getShort(14));
            colors = Integer.toUnsignedLong(b.getInt(32));
            if (colors == 0 && bits <= 8) colors = 1L << bits;
            int compression = b.getInt(16);
            if (header == 40 && (compression == 3 || compression == 6)) masks = compression == 3 ? 12 : 16;
            paletteSize = 4;
        }
        Server.require(Set.of(1, 4, 8, 16, 24, 32).contains(bits) && colors <= 256, "DIB 位深或调色板无效");
        long offset = 14L + header + masks + colors * paletteSize;
        Server.require(offset <= 14L + dib.length, "DIB 数据不完整");
        ByteBuffer file = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte)'B').put((byte)'M').putInt(dib.length + 14).putInt(0).putInt((int)offset);
        return file.array();
    }
    static BufferedImage whiteBackground(BufferedImage image) {
        var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null); g.dispose(); return rgb;
    }
    static void write(BufferedImage image, Path path, String format, int quality, int bmpBits) throws IOException {
        switch (format) {
            case "bmp", "dib" -> { try (var out = Files.newOutputStream(path)) { writeBitmap(image, out, bmpBits, format.equals("dib")); } }
            case "gif" -> { if (!ImageIO.write(image, "gif", path.toFile())) throw new IOException("GIF encoder unavailable"); }
            case "tiff" -> {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
                try (var out = ImageIO.createImageOutputStream(path.toFile())) {
                    writer.setOutput(out); ImageWriteParam params = writer.getDefaultWriteParam();
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); params.setCompressionType("Deflate");
                    writer.write(null, new IIOImage(image, null, null), params);
                } finally { writer.dispose(); }
            }
            case "heic" -> writeHeic(image, path, quality);
            default -> Server.writeImage(image, path, format, quality);
        }
    }
    static void writeBitmap(BufferedImage source, OutputStream output, int bits, boolean dib) throws IOException {
        Server.require(Set.of(1, 4, 8, 24).contains(bits), "位图位深仅支持 1、4、8、24 位");
        BufferedImage rgb = whiteBackground(source), indexed = null;
        int colors = bits == 24 ? 0 : 1 << bits;
        IndexColorModel palette = null;
        if (bits != 24) {
            if (bits == 8) indexed = new BufferedImage(rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);
            else {
                byte[] r = new byte[colors], g = new byte[colors], b = new byte[colors];
                int[] vga = {0x000000,0x800000,0x008000,0x808000,0x000080,0x800080,0x008080,0xc0c0c0,
                    0x808080,0xff0000,0x00ff00,0xffff00,0x0000ff,0xff00ff,0x00ffff,0xffffff};
                for (int i = 0; i < colors; i++) { int c = bits == 1 ? (i == 0 ? 0 : 0xffffff) : vga[i]; r[i] = (byte)(c >> 16); g[i] = (byte)(c >> 8); b[i] = (byte)c; }
                palette = new IndexColorModel(bits, colors, r, g, b);
                indexed = new BufferedImage(rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_BYTE_BINARY, palette);
            }
            Graphics2D g = indexed.createGraphics(); g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
            g.drawImage(rgb, 0, 0, null); g.dispose(); palette = (IndexColorModel)indexed.getColorModel();
        }
        int width = rgb.getWidth(), height = rgb.getHeight(), stride = ((width * bits + 31) / 32) * 4;
        int offset = 14 + 40 + colors * 4, dataSize = stride * height;
        ByteBuffer header = ByteBuffer.allocate(dib ? 40 : 54).order(ByteOrder.LITTLE_ENDIAN);
        if (!dib) header.put((byte)'B').put((byte)'M').putInt(offset + dataSize).putInt(0).putInt(offset);
        header.putInt(40).putInt(width).putInt(height).putShort((short)1).putShort((short)bits)
            .putInt(0).putInt(dataSize).putInt(2835).putInt(2835).putInt(colors).putInt(0);
        output.write(header.array());
        for (int i = 0; i < colors; i++) { output.write(palette.getBlue(i)); output.write(palette.getGreen(i)); output.write(palette.getRed(i)); output.write(0); }
        byte[] row = new byte[stride];
        for (int y = height - 1; y >= 0; y--) {
            Arrays.fill(row, (byte)0);
            for (int x = 0; x < width; x++) {
                if (bits == 24) { int c = rgb.getRGB(x,y); row[x*3]=(byte)c; row[x*3+1]=(byte)(c>>8); row[x*3+2]=(byte)(c>>16); }
                else { int sample = indexed.getRaster().getSample(x,y,0); int at = x * bits; row[at/8] |= (byte)(sample << (8 - bits - at % 8)); }
            }
            output.write(row);
        }
        rgb.flush(); if (indexed != null) indexed.flush();
    }
    static String heifTool(String name) {
        String override = System.getenv("HEIF_TOOLS_DIR");
        String exe = name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        if (override != null && !override.isBlank()) return Path.of(override, exe).toAbsolutePath().toString();
        Path local = Path.of(".tools", "heic", "ucrt64", "bin", exe).toAbsolutePath();
        if (Files.isRegularFile(local)) return local.toString();
        if (name.equals("heif-dec") && !System.getProperty("os.name").startsWith("Windows")) {
            for (String directory : Server.env("PATH", "").split(File.pathSeparator))
                if (Files.isExecutable(Path.of(directory, "heif-dec"))) return "heif-dec";
            return "heif-convert"; // libheif 1.17 used this command name.
        }
        return exe;
    }
    static boolean toolFound(String name) {
        String command = heifTool(name);
        if (Path.of(command).isAbsolute()) return Files.isRegularFile(Path.of(command));
        for (String directory : Server.env("PATH", "").split(File.pathSeparator))
            if (Files.isRegularFile(Path.of(directory, command))) return true;
        return false;
    }
    static void external(Path work, String... command) throws IOException {
        Path log = Files.createTempFile(work, "codec-", ".log"); Process process = null;
        try {
            process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new Server.Problem(400, "HEIC 处理超时，请选择较小的图片");
            if (process.exitValue() != 0) {
                System.err.println("HEIC tool failed (exit " + process.exitValue() + ")");
                throw new Server.Problem(400, "HEIC 转换失败，请检查图片及 HEIC 编解码工具");
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Conversion interrupted", ex); }
        catch (IOException ex) { throw new Server.Problem(400, "缺少 HEIC 工具：Windows 请先运行 setup-heic.cmd；Linux 请安装 libheif 工具"); }
        finally {
            if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            Files.deleteIfExists(log);
        }
    }
    static void writeHeic(BufferedImage image, Path output, int quality) throws IOException {
        Path temp = Files.createTempFile(output.toAbsolutePath().getParent(), "heic-", ".png");
        BufferedImage rgb = whiteBackground(image);
        try {
            ImageIO.write(rgb, "png", temp.toFile());
            external(temp.getParent(), heifTool("heif-enc"), "--encoder", "x265", "-p", "preset=fast", "-p", "x265:pools=2",
                "-q", Integer.toString(quality), "-o", output.toAbsolutePath().toString(), temp.toString());
            Server.require(Files.isRegularFile(output) && Files.size(output) > 20, "HEIC 编码没有生成有效文件");
        } finally { rgb.flush(); Files.deleteIfExists(temp); }
    }
    static BufferedImage readHeic(Path input) throws IOException {
        // Bound declared image dimensions before starting the native decoder; keep libheif's own limits enabled.
        byte[] content = Files.readAllBytes(input); boolean hasDimensions = false;
        ByteBuffer bytes = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN);
        for (int at = 4; at + 16 <= content.length; at++) {
            if (bytes.getInt(at) == 0x69737065 && bytes.getInt(at - 4) == 20) {
                dimensions(Integer.toUnsignedLong(bytes.getInt(at + 8)), Integer.toUnsignedLong(bytes.getInt(at + 12))); hasDimensions = true;
            }
        }
        Server.require(hasDimensions, "HEIC 图片尺寸信息无效");
        Path directory = Files.createTempDirectory(input.toAbsolutePath().getParent(), "heic-decode-");
        Path png = directory.resolve("first.png");
        try {
            external(directory, heifTool("heif-dec"), "--quiet", input.toAbsolutePath().toString(), png.toString());
            if (!Files.isRegularFile(png)) png = directory.resolve("first-1.png");
            Server.require(Files.isRegularFile(png), "HEIC 解码没有生成图片");
            return read(png);
        } finally {
            // Dedicated decoder directory also contains possible extracted metadata; never touch the upload directory.
            try (var paths = Files.list(directory)) { for (Path p : paths.toList()) Files.deleteIfExists(p); }
            Files.deleteIfExists(directory);
        }
    }
}

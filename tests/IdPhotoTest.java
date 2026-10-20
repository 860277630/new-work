package formatfactory;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Real local inference fixture and validation, not a fake mask provider. */
public final class IdPhotoTest {
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of("build/idphoto-test/portrait-fixture.png");
        if(!IdPhoto.available()||!Files.isRegularFile(fixture)){System.out.println("SKIP: ID photo inference needs local models and public portrait fixture");return;}
        Path folder=Path.of("build/idphoto-test");Files.createDirectories(folder);
        Server.Request request=new Server.Request();request.operation="idPhoto";request.format="jpg";request.quality=95;request.fileIds=java.util.List.of("test");
        long started=System.nanoTime();
        byte[] original=Files.readAllBytes(fixture);
        Path white=folder.resolve("white-25x35.jpg");IdPhoto.generate(fixture,white,request);
        verify(white,295,413,Color.WHITE,300);
        request.photoBackground="blue";request.format="png";
        Path blue=folder.resolve("blue-25x35.png");IdPhoto.generate(fixture,blue,request);
        verify(blue,295,413,new Color(67,142,219),300);
        request.photoBackground="red";request.photoWidthMm=35;request.photoHeightMm=49;request.photoZoom=0.9;
        Path red=folder.resolve("red-35x49.png");IdPhoto.generate(fixture,red,request);
        verify(red,413,579,new Color(213,40,42),300);
        check(Arrays.equals(original,Files.readAllBytes(fixture)),"source photo changed");
        BufferedImage empty=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=empty.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,512,512);graphics.dispose();
        Path blank=folder.resolve("no-face.png");ImageIO.write(empty,"png",blank.toFile());
        boolean rejected=false;try{IdPhoto.generate(blank,folder.resolve("invalid.png"),request);}catch(Server.Problem error){rejected=error.getMessage().contains("人脸");}
        check(rejected,"image without face accepted");
        BufferedImage portrait=ImageIO.read(fixture.toFile()),pair=new BufferedImage(1024,512,BufferedImage.TYPE_INT_RGB);
        graphics=pair.createGraphics();graphics.drawImage(portrait,0,0,null);graphics.drawImage(portrait,512,0,null);graphics.dispose();
        Path multiple=folder.resolve("multiple-faces.png");ImageIO.write(pair,"png",multiple.toFile());
        rejected=false;try{IdPhoto.generate(multiple,folder.resolve("invalid-multiple.png"),request);}catch(Server.Problem error){rejected=error.getMessage().contains("多个人脸");}
        check(rejected,"multiple faces accepted");
        request.photoWidthMm=200;rejected=false;try{Server.validate(request);}catch(Server.Problem error){rejected=true;}
        check(rejected,"invalid physical dimensions accepted");
        System.out.println("PASS: real matting, single-face crop, white/blue/red backgrounds, exact sizes, JPEG/PNG 300 DPI, immutable source, no-face and multiple-face rejection; total " + (System.nanoTime()-started)/1_000_000 + "ms");
    }
    static void verify(Path file,int width,int height,Color background,int dpi) throws Exception {
        BufferedImage image=ImageIO.read(file.toFile());check(image!=null&&image.getWidth()==width&&image.getHeight()==height,"output size incorrect");
        Color corner=new Color(image.getRGB(0,0));check(Math.abs(corner.getRed()-background.getRed())<8&&Math.abs(corner.getGreen()-background.getGreen())<8&&Math.abs(corner.getBlue()-background.getBlue())<8,"background not replaced");
        long subject=0;for(int y=height/5;y<height*4/5;y++)for(int x=width/4;x<width*3/4;x++)if((image.getRGB(x,y)&0xffffff)!=(background.getRGB()&0xffffff))subject++;
        check(subject>width*height/10,"portrait is blank");
        try(var stream=ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader=ImageIO.getImageReaders(stream).next();reader.setInput(stream);
            try{
                if(file.toString().endsWith("jpg")) {
                    IIOMetadataNode root=(IIOMetadataNode)reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0");
                    IIOMetadataNode jfif=(IIOMetadataNode)root.getElementsByTagName("app0JFIF").item(0);
                    check(jfif.getAttribute("resUnits").equals("1")&&jfif.getAttribute("Xdensity").equals(String.valueOf(dpi)),"JPEG DPI incorrect");
                } else {
                    IIOMetadataNode root=(IIOMetadataNode)reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0");
                    IIOMetadataNode phys=(IIOMetadataNode)root.getElementsByTagName("pHYs").item(0);
                    check(phys!=null&&phys.getAttribute("pixelsPerUnitXAxis").equals(String.valueOf(Math.round(dpi/0.0254))),"PNG DPI incorrect");
                }
            }finally{reader.dispose();}
        }
    }
}

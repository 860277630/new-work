package formatfactory;

import ai.onnxruntime.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

/** Local MODNet portrait matting + RFB face location. Preserves the original face. */
public final class IdPhotoWorker {
    static final String MATTE_SHA = "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9";
    record Face(float x1, float y1, float x2, float y2, float score) {
        float width() { return x2 - x1; } float height() { return y2 - y1; }
    }
    public static void main(String[] args) {
        try {
            Server.Request request = Server.JSON.fromJson(args[3], Server.Request.class);
            Server.validate(request);
            generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), request);
        } catch (Throwable exception) {
            String message = exception instanceof Server.Problem ? exception.getMessage() : "本地 AI 处理失败，请检查模型、运行库和照片";
            System.out.println("IDPHOTO_ERROR:" + message.replace('\n', ' ').replace('\r', ' '));
            System.exit(1);
        }
    }
    static void generate(Path input, Path output, Path models, Server.Request request) throws Exception {
        checkHash(models.resolve("modnet.onnx"), MATTE_SHA);
        checkHash(models.resolve("face-rfb-320.onnx"), "34cd7e60aeff28744c657de7a3dc64e872d506741de66987f3426f2b79f88017");
        BufferedImage source = orient(ImageCodec.read(input), input);
        if (Math.max(source.getWidth(), source.getHeight()) > 2000) {
            double scale = 2000.0 / Math.max(source.getWidth(), source.getHeight());
            BufferedImage small = scaled(source, Math.max(1, (int)(source.getWidth()*scale)), Math.max(1, (int)(source.getHeight()*scale)));
            source.flush(); source = small;
        }
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            Face face;
            try (var detector = environment.createSession(models.resolve("face-rfb-320.onnx").toString(), options)) {
                List<Face> faces = detect(environment, detector, source);
                Server.require(!faces.isEmpty(), "未检测到清晰人脸，请选择头顶完整、无遮挡的正面半身照片");
                Server.require(faces.size() == 1, "检测到多个人脸，请选择只有一个人的照片");
                face = faces.get(0);
                Server.require(face.height() > 0.08 && face.width() > 0.05, "人脸太小，请选择更清晰的正面半身照片");
            }
            BufferedImage portrait;
            try (var matte = environment.createSession(models.resolve("modnet.onnx").toString(), options)) {
                portrait = matting(environment, matte, source);
            }
            BufferedImage result = compose(portrait, face, request);
            write(result, output, request.format, request.quality, request.photoDpi);
            portrait.flush(); result.flush(); source.flush();
        }
    }
    static List<Face> detect(OrtEnvironment environment, OrtSession detector, BufferedImage image) throws Exception {
        try (OnnxTensor tensor = tensor(environment, image, 320, 240, true);
             var results = detector.run(Map.of(detector.getInputNames().iterator().next(), tensor))) {
            float[][][] scores = (float[][][]) results.get("scores").orElseThrow().getValue();
            float[][][] boxes = (float[][][]) results.get("boxes").orElseThrow().getValue();
            List<Face> candidates = new ArrayList<>();
            for (int i=0; i<scores[0].length; i++) if (scores[0][i][1] >= 0.7f) {
                float[] box = boxes[0][i];
                Face face = new Face(clamp(box[0]), clamp(box[1]), clamp(box[2]), clamp(box[3]), scores[0][i][1]);
                if (face.width()>0 && face.height()>0) candidates.add(face);
            }
            candidates.sort(Comparator.comparingDouble(Face::score).reversed());
            List<Face> kept = new ArrayList<>();
            for (Face face : candidates) if (kept.stream().noneMatch(other -> overlap(face, other)>0.3)) {
                kept.add(face); if (kept.size()>5) break;
            }
            return kept;
        }
    }
    static double overlap(Face a, Face b) {
        double intersection = Math.max(0, Math.min(a.x2,b.x2)-Math.max(a.x1,b.x1)) * Math.max(0, Math.min(a.y2,b.y2)-Math.max(a.y1,b.y1));
        return intersection / Math.max(1e-9, a.width()*a.height()+b.width()*b.height()-intersection);
    }
    static BufferedImage matting(OrtEnvironment environment, OrtSession model, BufferedImage source) throws Exception {
        // The pinned export accepts NCHW tensors with spatial dimensions divisible by 32.
        int width = source.getWidth() >= source.getHeight() ? 512 : Math.max(32, Math.round(512f*source.getWidth()/source.getHeight()/32)*32);
        int height = source.getHeight() >= source.getWidth() ? 512 : Math.max(32, Math.round(512f*source.getHeight()/source.getWidth()/32)*32);
        try (OnnxTensor tensor = tensor(environment, source, width, height, false);
             var results = model.run(Map.of(model.getInputNames().iterator().next(), tensor))) {
            float[][] mask = ((float[][][][]) results.get(0).getValue())[0][0];
            BufferedImage small = new BufferedImage(mask[0].length, mask.length, BufferedImage.TYPE_INT_ARGB);
            for (int y=0; y<small.getHeight(); y++) for (int x=0; x<small.getWidth(); x++) {
                int gray = Math.round(clamp(mask[y][x])*255); small.setRGB(x,y,0xff000000 | gray*0x010101);
            }
            BufferedImage full = scaled(small, source.getWidth(), source.getHeight()); small.flush();
            BufferedImage portrait = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y=0; y<portrait.getHeight(); y++) for (int x=0; x<portrait.getWidth(); x++) {
                int pixel = source.getRGB(x,y), alpha = (full.getRGB(x,y)&255)*(pixel>>>24)/255;
                portrait.setRGB(x,y,alpha<<24 | pixel&0x00ffffff);
            }
            full.flush(); return portrait;
        }
    }
    static OnnxTensor tensor(OrtEnvironment environment, BufferedImage source, int width, int height, boolean face) throws Exception {
        BufferedImage image = scaled(source, width, height);
        float[] data = new float[3*width*height]; int plane=width*height;
        for (int y=0; y<height; y++) for (int x=0; x<width; x++) {
            int pixel=image.getRGB(x,y), index=y*width+x;
            for (int c=0;c<3;c++) {
                int value=pixel>>(16-c*8)&255;
                data[c*plane+index]=face ? (value-127f)/128f : value/127.5f-1f;
            }
        }
        image.flush(); return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), new long[]{1,3,height,width});
    }
    static BufferedImage compose(BufferedImage portrait, Face face, Server.Request request) {
        int width=pixels(request.photoWidthMm,request.photoDpi), height=pixels(request.photoHeightMm,request.photoDpi);
        BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=result.createGraphics();
        graphics.setColor(background(request.photoBackground)); graphics.fillRect(0,0,width,height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double faceHeight=face.height()*portrait.getHeight(), center=(face.x1+face.x2)/2*portrait.getWidth();
        int top=(int)(face.y1*portrait.getHeight());
        int fromX=Math.max(0,(int)(center-face.width()*portrait.getWidth()*0.85));
        int toX=Math.min(portrait.getWidth(),(int)(center+face.width()*portrait.getWidth()*0.85));
        int startY=Math.max(0,(int)(top-faceHeight*0.85));
        boolean found=false;
        for(int y=startY;y<top && !found;y++) {
            int foreground=0;
            for(int x=fromX;x<toX;x++) if((portrait.getRGB(x,y)>>>24)>128) foreground++;
            if(foreground>Math.max(2,(toX-fromX)/20)){top=y;found=true;}
        }
        double headHeight=face.y2*portrait.getHeight()-top;
        double scale=height*0.62/Math.max(1,headHeight)*request.photoZoom;
        double offsetX=width/2.0-center*scale+width*request.photoOffsetX/100;
        double offsetY=height*0.08-top*scale+height*request.photoOffsetY/100;
        graphics.drawImage(portrait,(int)Math.round(offsetX),(int)Math.round(offsetY),
            (int)Math.round(portrait.getWidth()*scale),(int)Math.round(portrait.getHeight()*scale),null);
        graphics.dispose(); return result;
    }
    static int pixels(int mm,int dpi) { return (int)Math.round(mm*dpi/25.4); }
    static Color background(String value) { return switch(value) { case "blue" -> new Color(67,142,219); case "red" -> new Color(213,40,42); default -> Color.WHITE; }; }
    static BufferedImage scaled(BufferedImage source,int width,int height) {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source,0,0,width,height,null);g.dispose();return image;
    }
    static float clamp(float value) { return Float.isFinite(value) ? Math.max(0,Math.min(1,value)) : 0; }
    static void checkHash(Path model,String expected) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(model)){byte[] buffer=new byte[65536];int length;while((length=input.read(buffer))!=-1)digest.update(buffer,0,length);}
        Server.require(HexFormat.of().formatHex(digest.digest()).equals(expected),"本地抠图模型校验失败，请重新运行 setup-idphoto.cmd");
    }
    static void write(BufferedImage image,Path output,String format,int quality,int dpi) throws Exception {
        ImageWriter writer=ImageIO.getImageWritersByFormatName(format).next();
        try(ImageOutputStream stream=ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(stream);ImageWriteParam params=writer.getDefaultWriteParam();
            if(format.equals("jpg")){params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);params.setCompressionQuality(quality/100f);}
            IIOMetadata metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),params);
            if(format.equals("jpg")) {
                IIOMetadataNode root=(IIOMetadataNode)metadata.getAsTree("javax_imageio_jpeg_image_1.0");
                IIOMetadataNode jfif=(IIOMetadataNode)root.getElementsByTagName("app0JFIF").item(0);
                jfif.setAttribute("resUnits","1");jfif.setAttribute("Xdensity",String.valueOf(dpi));jfif.setAttribute("Ydensity",String.valueOf(dpi));
                metadata.setFromTree("javax_imageio_jpeg_image_1.0",root);
            } else {
                IIOMetadataNode root=new IIOMetadataNode("javax_imageio_png_1.0"),phys=new IIOMetadataNode("pHYs");
                phys.setAttribute("pixelsPerUnitXAxis",String.valueOf(Math.round(dpi/0.0254)));
                phys.setAttribute("pixelsPerUnitYAxis",String.valueOf(Math.round(dpi/0.0254)));phys.setAttribute("unitSpecifier","meter");
                root.appendChild(phys);metadata.mergeTree("javax_imageio_png_1.0",root);
            }
            writer.write(null,new IIOImage(image,null,metadata),params);
        } finally {writer.dispose();}
    }
    // JPEG EXIF orientation is applied only for the portrait tool.
    static BufferedImage orient(BufferedImage source,Path path) throws Exception {
        int orientation=exifOrientation(path);
        if(orientation<=1 || orientation>8)return source;
        int w=source.getWidth(),h=source.getHeight();
        BufferedImage result=new BufferedImage(orientation>=5?h:w,orientation>=5?w:h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int dx=x,dy=y;
            switch(orientation){case 2->dx=w-1-x;case 3->{dx=w-1-x;dy=h-1-y;}case 4->dy=h-1-y;
                case 5->{dx=y;dy=x;}case 6->{dx=h-1-y;dy=x;}case 7->{dx=h-1-y;dy=w-1-x;}case 8->{dx=y;dy=w-1-x;}}
            result.setRGB(dx,dy,source.getRGB(x,y));
        }
        source.flush();return result;
    }
    static int exifOrientation(Path path) throws Exception {
        try(var input=new java.io.DataInputStream(Files.newInputStream(path))){
            if(input.readUnsignedShort()!=0xffd8)return 1;
            while(true){int marker=input.readUnsignedByte();if(marker!=255)return 1;do{marker=input.readUnsignedByte();}while(marker==255);
                if(marker==0xda || marker==0xd9)return 1;if(marker==0x01 || marker>=0xd0&&marker<=0xd7)continue;
                int size=input.readUnsignedShort()-2;if(size<0)return 1;byte[] data=input.readNBytes(size);if(data.length!=size)return 1;
                if(marker!=0xe1 || data.length<14 || data[0]!='E'||data[1]!='x'||data[2]!='i'||data[3]!='f'||data[4]!=0||data[5]!=0)continue;
                ByteBuffer b=ByteBuffer.wrap(data).order(data[6]=='I'&&data[7]=='I'?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
                int offset=b.getInt(10);if(offset<8 || offset>data.length-8)return 1;int start=6+offset,count=Short.toUnsignedInt(b.getShort(start));
                for(int i=0;i<count;i++){int entry=start+2+i*12;if(entry<0||entry+12>data.length)return 1;
                    if(Short.toUnsignedInt(b.getShort(entry))==0x112 && b.getShort(entry+2)==3 && b.getInt(entry+4)==1)return Short.toUnsignedInt(b.getShort(entry+8));}
                return 1;
            }
        }catch(java.io.EOFException|IndexOutOfBoundsException exception){return 1;}
    }
}

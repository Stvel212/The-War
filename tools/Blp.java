import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Iterator;
import javax.imageio.*;
import javax.imageio.stream.*;

/**
 * BLP1 (JPEG-сжатие, как у текстур Warcraft III) <-> PNG.
 *   java -cp tools Blp encode in.png out.blp [width height] [quality 0..1]   (по умолчанию размер картинки, 0.92)
 *   java -cp tools Blp decode in.blp out.png
 * Внутри BLP лежит обычный JPEG с 4 каналами в порядке B,G,R,A без цветового преобразования; общий JPEG-заголовок
 * пишем пустым (весь JPEG в первом мипе) — так делают BLPConverter/BLP Lab, игра склеивает заголовок с мипом.
 */
public class Blp {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("decode")) { decode(Paths.get(a[1]), Paths.get(a[2])); return; }
        BufferedImage src = ImageIO.read(new File(a[1]));
        int w = a.length > 4 ? Integer.parseInt(a[3]) : src.getWidth(), h = a.length > 4 ? Integer.parseInt(a[4]) : src.getHeight();
        float q = a.length > 5 ? Float.parseFloat(a[5]) : 0.92f;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        byte[] jpeg = toJpeg(img, q);
        ByteBuffer b = ByteBuffer.allocate(156 + 4 + jpeg.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("BLP1".getBytes());
        b.putInt(0);        // compression: JPEG
        b.putInt(8);        // alpha bits
        b.putInt(w).putInt(h);
        b.putInt(4);        // extra (как у текстур из редактора)
        b.putInt(0);        // без мипмапов (лоадскрин)
        for (int i = 0; i < 16; i++) b.putInt(i == 0 ? 160 : 0);            // mip offsets
        for (int i = 0; i < 16; i++) b.putInt(i == 0 ? jpeg.length : 0);   // mip sizes
        b.putInt(0);        // размер общего JPEG-заголовка
        b.put(jpeg);
        Files.write(Paths.get(a[2]), b.array());
        System.out.println(a[2] + ": " + w + "x" + h + ", " + b.capacity() + " bytes");
    }

    /** JPEG из 4-канального растра B,G,R,A (writeRaster не делает преобразования цвета) */
    static byte[] toJpeg(BufferedImage img, float quality) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        WritableRaster r = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, w, h, 4, null);
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] d = ((DataBufferByte) r.getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) {
            d[i * 4] = (byte) px[i]; d[i * 4 + 1] = (byte) (px[i] >> 8); d[i * 4 + 2] = (byte) (px[i] >> 16); d[i * 4 + 3] = (byte) (px[i] >>> 24);
        }
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = wr.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(bo)) { wr.setOutput(os); wr.write(null, new IIOImage(r, null, null), p); }
        wr.dispose();
        return bo.toByteArray();
    }

    static void decode(Path in, Path out) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(in)).order(ByteOrder.LITTLE_ENDIAN);
        b.position(4);
        int comp = b.getInt(), alpha = b.getInt(), w = b.getInt(), h = b.getInt(); b.getInt(); b.getInt();
        int[] off = new int[16], len = new int[16];
        for (int i = 0; i < 16; i++) off[i] = b.getInt();
        for (int i = 0; i < 16; i++) len[i] = b.getInt();
        if (comp != 0) throw new RuntimeException("not a JPEG BLP");
        int hs = b.getInt();
        byte[] jpeg = new byte[hs + len[0]];
        b.get(jpeg, 0, hs);
        System.arraycopy(b.array(), off[0], jpeg, hs, len[0]);
        ImageReader rd = ImageIO.getImageReadersByFormatName("jpeg").next();
        rd.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg)));
        Raster r = rd.readRaster(0, null);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] s = new int[4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            r.getPixel(x, y, s);
            img.setRGB(x, y, (alpha == 0 ? 255 : s[3]) << 24 | s[2] << 16 | s[1] << 8 | s[0]);
        }
        ImageIO.write(img, "png", out.toFile());
        System.out.println(in + ": " + w + "x" + h + ", header " + hs + " + mip0 " + len[0] + " -> " + out);
    }
}

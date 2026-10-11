import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Текстуры модели MDX без каталога (импорт Just Fight лежит в корне архива) переводит в каталог prefix
 * (в The War импорты текстур — в imports/textures): в чанке TEXS путь — поле фиксированной длины 260 байт.
 *
 *   java PatchMdxTextures <вход.mdx> <выход.mdx> <префикс, например textures\>
 */
public class PatchMdxTextures {
    public static void main(String[] a) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(a[0]));
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 4, patched = 0;
        while (pos + 8 <= b.length) {
            String tag = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = bb.getInt(pos + 4);
            if (tag.equals("TEXS")) {
                for (int e = pos + 8; e + 268 <= pos + 8 + size; e += 268) {
                    int p = e + 4, len = 0;
                    while (len < 260 && b[p + len] != 0) len++;
                    String path = new String(b, p, len, StandardCharsets.US_ASCII);
                    if (len > 0 && !path.contains("\\") && !path.contains("/")) {
                        byte[] np = (a[2] + path).getBytes(StandardCharsets.US_ASCII);
                        if (np.length >= 260) throw new IllegalStateException("path too long: " + path);
                        java.util.Arrays.fill(b, p, p + 260, (byte) 0);
                        System.arraycopy(np, 0, b, p, np.length);
                        patched++;
                    }
                }
            }
            pos += 8 + size;
        }
        Files.write(Paths.get(a[1]), b);
        System.out.println(a[0] + ": " + patched + " texture path(s) patched");
    }
}

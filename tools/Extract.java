import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;

public class Extract {
    public static void main(String[] a) throws Exception {
        Path map = Paths.get(a[0]);
        Path out = Paths.get(a[1]);
        Class<?> C = Class.forName("org.inwc3.jmpq.MpqArchive");
        Object ar = C.getMethod("open", Path.class).invoke(null, map);
        System.out.println("blocks=" + C.getMethod("blockCount").invoke(ar)
            + " enum=" + C.getMethod("enumerationState").invoke(ar)
            + " unnamed=" + C.getMethod("unnamedBlockCount").invoke(ar)
            + " integrity=" + C.getMethod("integrity").invoke(ar));
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) C.getMethod("names").invoke(ar);
        System.out.println("named files: " + names.size());
        Method read = C.getMethod("read", String.class);
        int ok = 0;
        for (String n : names) {
            String rel = n.replace((char)92, '/');
            Path dst = out.resolve(rel);
            try {
                Files.createDirectories(dst.getParent());
                Files.write(dst, (byte[]) read.invoke(ar, n));
                ok++;
            } catch (Exception e) {
                System.out.println("FAIL " + n + ": " + (e.getCause() != null ? e.getCause() : e));
            }
        }
        System.out.println("extracted " + ok);
        ((AutoCloseable) ar).close();
    }
}

import java.nio.file.*;
import java.lang.reflect.*;
public class ReadMpq {
    public static void main(String[] a) throws Exception {
        Class<?> C = Class.forName("org.inwc3.jmpq.MpqArchive");
        Object ar = C.getMethod("open", Path.class).invoke(null, Paths.get(a[0]));
        Method read = C.getMethod("read", String.class);
        for (int i = 2; i < a.length; i++) {
            String n = a[i].replace('/', (char)92);
            try { byte[] b = (byte[]) read.invoke(ar, n);
                Path p = Paths.get(a[1], a[i].replace('/', '_')); Files.write(p, b); System.out.println("OK " + n + " " + b.length);
            } catch (Exception e) { System.out.println("NO " + n + " " + (e.getCause()!=null?e.getCause():e)); }
        }
    }
}

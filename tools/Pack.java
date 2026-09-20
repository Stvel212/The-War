import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import java.util.stream.*;

/** Pack a folder-map into a .w3x archive, reusing the HM3W header of a template map. Uses jmpq from wurstscript.jar (reflection: jar is Java 25). */
public class Pack {
    public static void main(String[] a) throws Exception {
        Path template = Paths.get(a[0]), folder = Paths.get(a[1]), out = Paths.get(a[2]);
        ClassLoader cl = Pack.class.getClassLoader();
        Class<?> A = cl.loadClass("org.inwc3.jmpq.MpqArchive"), W = cl.loadClass("org.inwc3.jmpq.MpqArchiveWriter"), O = cl.loadClass("org.inwc3.jmpq.MpqWriteOptions");
        Object ar = A.getMethod("open", Path.class).invoke(null, template);
        Object opts = O.getMethod("recompressed").invoke(null);
        opts = O.getMethod("withPrefix", boolean.class).invoke(opts, true);
        opts = O.getMethod("withListfile", boolean.class).invoke(opts, true);
        Object w = W.getMethod("from", A, O).invoke(null, ar, opts);
        @SuppressWarnings("unchecked") List<String> old = new ArrayList<>((List<String>) W.getMethod("names").invoke(w));
        Method remove = W.getMethod("remove", String.class), put = W.getMethod("put", String.class, Path.class);
        for (String n : old) remove.invoke(w, n);
        int c = 0;
        try (Stream<Path> s = Files.walk(folder)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                String rel = folder.relativize(p).toString().replace('/', '\\');
                put.invoke(w, rel, p); c++;
            }
        }
        W.getMethod("save", Path.class).invoke(w, out);
        ((AutoCloseable) ar).close();
        System.out.println("packed " + c + " files -> " + out);
    }
}

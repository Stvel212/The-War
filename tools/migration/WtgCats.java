import java.nio.file.*; import java.nio.*; import java.util.*; import java.nio.charset.StandardCharsets;

/** Recover trigger -> category mapping from a v7 WTG without TriggerData: parse header categories, then locate each trigger record by name. */
public class WtgCats {
    static byte[] d; static ByteBuffer b;
    static String cstr(){ int s=b.position(); while(b.get()!=0){} int e=b.position()-1; return new String(d, s, e-s, StandardCharsets.UTF_8); }
    static java.io.PrintStream out; public static void main(String[] a) throws Exception { out = new java.io.PrintStream(new java.io.FileOutputStream(a[2]), true, "UTF-8");
        d = Files.readAllBytes(Paths.get(a[0])); b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        b.getInt(); int ver=b.getInt(); int nc=b.getInt(); Map<Integer,String> cats=new LinkedHashMap<>();
        for(int i=0;i<nc;i++){ int id=b.getInt(); String n=cstr(); if(ver==7) b.getInt(); cats.put(id,n); }
        out.println("categories: "+cats);
        b.getInt(); int nv=b.getInt();
        for(int i=0;i<nv;i++){ cstr(); cstr(); b.getInt(); b.getInt(); if(ver==7) b.getInt(); b.getInt(); cstr(); }
        int nt=b.getInt(); out.println("triggers: "+nt+"  (scan starts at "+b.position()+")");
        int start=b.position();
        List<String> names = Files.readAllLines(Paths.get(a[1]));
        // scan sequentially: each trigger record starts with name; we search names in order from current position
        int pos=start;
        for(String name : names){
            byte[] nb=(name+"\0").getBytes(StandardCharsets.UTF_8);
            int f=indexOf(d, nb, pos);
            if(f<0){ nb=(name.replace('_',' ')+"\0").getBytes(StandardCharsets.UTF_8); f=indexOf(d, nb, pos); }
            if(f<0){ out.println("?? "+name); continue; }
            b.position(f); String nm=cstr(); String desc=cstr(); int isComment=b.getInt(); int enabled=b.getInt(); int custom=b.getInt(); int initOff=b.getInt(); int runInit=b.getInt(); int cat=b.getInt(); int neca=b.getInt();
            out.println(cats.getOrDefault(cat,"cat"+cat)+"\t"+nm+"\t"+(custom==1?"JASS":"GUI")+"\t"+(enabled==1?"on":"OFF")+"\t"+(initOff==1?"initOff":"")+"\tecas="+neca+"\t"+desc.replace('\n',' '));
            pos=b.position();
        }
    }
    static int indexOf(byte[] h, byte[] n, int from){ outer: for(int i=from;i<=h.length-n.length;i++){ for(int j=0;j<n.length;j++) if(h[i+j]!=n[j]) continue outer; return i; } return -1; }
}

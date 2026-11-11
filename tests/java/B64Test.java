import com.clean.stickman.core.B64;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

public class B64Test {
    public void testMatchesJdk() {
        T.eq("", B64.encode(new byte[0]), "empty");
        T.eq("Zg==", B64.encode("f"), "one byte");
        T.eq("Zm8=", B64.encode("fo"), "two bytes");
        T.eq("Zm9v", B64.encode("foo"), "three bytes");
        T.eq("", B64.encode((String) null), "null");
        Random r = new Random(7);
        for (int n = 0; n < 300; n++) {
            byte[] b = new byte[r.nextInt(200)];
            r.nextBytes(b);
            T.eq(java.util.Base64.getEncoder().encodeToString(b), B64.encode(b), "random " + b.length);
        }
    }

    /** java.util.Base64 is Android 8+: it broke setup on Fire OS 5/6 (API 22/25). Keep it out of the app. */
    public void testNoJavaUtilBase64InApp() throws Exception {
        File src = new File("src");
        T.eq(true, src.isDirectory(), "run from the repo root");
        StringBuilder bad = new StringBuilder();
        scan(src, bad);
        T.eq("", bad.toString(), "files using java.util.Base64");
    }

    private static void scan(File f, StringBuilder bad) throws Exception {
        if (f.isDirectory()) { for (File c : f.listFiles()) scan(c, bad); return; }
        if (!f.getName().endsWith(".java") || f.getName().equals("B64.java")) return;
        if (new String(Files.readAllBytes(f.toPath()), "UTF-8").contains("java.util.Base64")) bad.append(f.getPath()).append(' ');
    }
}

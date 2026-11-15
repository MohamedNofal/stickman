import com.clean.stickman.core.Axml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AxmlTest {
    static byte[] fixture(String n) throws Exception { return Files.readAllBytes(Paths.get("tests/fixtures/axml/" + n)); }

    public void testOurOwnManifest() throws Exception {
        Axml.Info i = Axml.read(fixture("stickman.axml"));
        T.eq("com.clean.stickman", i.pkg, "package");
        T.eq(23L, i.versionCode, "version code");
        T.eq("1.9.6", i.versionName, "version name");
    }

    public void testARealAppsManifest() throws Exception {
        Axml.Info i = Axml.read(fixture("real-utf16.axml"));   // Moonlight 12.2, as aapt2 reads it
        T.eq("com.limelight", i.pkg, "package");
        T.eq(315L, i.versionCode, "version code");
        T.eq("12.2", i.versionName, "version name");
    }

    public void testUtf8StringPool() {
        Axml.Info i = Axml.read(build("org.example.tv", 4201, true, true));
        T.eq("org.example.tv", i.pkg, "package");
        T.eq(4201L, i.versionCode, "version code");
    }

    public void testVersionCodeByResourceIdWhenNamesAreStripped() {
        // obfuscators blank the attribute names; the resource map still says which attribute it is
        Axml.Info i = Axml.read(build("org.example.tv", 77, false, false));
        T.eq(77L, i.versionCode, "found by id 0x0101021b");
    }

    public void testGarbageIsNullNeverACrash() throws Exception {
        T.eq(null, Axml.read(null), "null");
        T.eq(null, Axml.read(new byte[0]), "empty");
        T.eq(null, Axml.read("<manifest package=\"a.b\"/>".getBytes(StandardCharsets.UTF_8)), "text XML is not a compiled manifest");
        byte[] real = fixture("real-utf16.axml");
        for (int cut : new int[] { 9, 40, 300, real.length / 2, real.length - 1 }) {
            byte[] part = new byte[cut];
            System.arraycopy(real, 0, part, 0, cut);
            Axml.read(part);   // must not throw
        }
        byte[] noise = real.clone();
        for (int k = 8; k < noise.length; k += 7) noise[k] ^= 0x5a;
        Axml.read(noise);      // must not throw
    }

    public void testAPackageNameThatIsNotOneIsRefused() {
        T.eq(null, Axml.read(build("rm -rf /", 1, false, true)), "not a package name");
    }

    /** A minimal compiled manifest: string pool, resource map, one start element with package + versionCode. */
    static byte[] build(String pkg, int versionCode, boolean utf8, boolean named) {
        String[] strings = { named ? "versionCode" : "", "package", "manifest", pkg };
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strings.length];
        for (int k = 0; k < strings.length; k++) {
            offs[k] = data.size();
            String s = strings[k];
            if (utf8) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                data.write(s.length()); data.write(b.length); data.write(b, 0, b.length); data.write(0);
            } else {
                byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
                data.write(s.length() & 0xff); data.write(s.length() >> 8); data.write(b, 0, b.length); data.write(0); data.write(0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        ByteArrayOutputStream pool = new ByteArrayOutputStream();
        int poolSize = 28 + 4 * strings.length + data.size();
        u16(pool, 0x0001); u16(pool, 28); i32(pool, poolSize); i32(pool, strings.length); i32(pool, 0);
        i32(pool, utf8 ? 0x100 : 0); i32(pool, 28 + 4 * strings.length); i32(pool, 0);
        for (int o : offs) i32(pool, o);
        pool.write(data.toByteArray(), 0, data.size());
        ByteArrayOutputStream map = new ByteArrayOutputStream();
        u16(map, 0x0180); u16(map, 8); i32(map, 12); i32(map, 0x0101021b);        // string 0 = android:versionCode
        ByteArrayOutputStream el = new ByteArrayOutputStream();
        u16(el, 0x0102); u16(el, 16); i32(el, 16 + 20 + 2 * 20); i32(el, 1); i32(el, -1);
        i32(el, -1); i32(el, 2); u16(el, 20); u16(el, 20); u16(el, 2); u16(el, 0); u16(el, 0); u16(el, 0);
        i32(el, -1); i32(el, 0); i32(el, -1); u16(el, 8); el.write(0); el.write(0x10); i32(el, versionCode);   // int
        i32(el, -1); i32(el, 1); i32(el, 3); u16(el, 8); el.write(0); el.write(0x03); i32(el, 3);             // string
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u16(out, 0x0003); u16(out, 8); i32(out, 8 + pool.size() + map.size() + el.size());
        out.write(pool.toByteArray(), 0, pool.size()); out.write(map.toByteArray(), 0, map.size()); out.write(el.toByteArray(), 0, el.size());
        return out.toByteArray();
    }

    static void u16(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
    static void i32(ByteArrayOutputStream o, int v) { u16(o, v & 0xffff); u16(o, (v >> 16) & 0xffff); }
}

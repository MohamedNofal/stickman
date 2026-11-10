import com.clean.stickman.core.KeyLayout;

import java.util.Map;

public class KeyLayoutTest {
    public void testParse() {
        Map<Integer, String> m = KeyLayout.parse("# remote\nkey 172   HOME\nkey 158 BACK WAKE\nkey usage 0x0c006F BRIGHTNESS_UP\n"
            + "key 0x74 POWER # hex\naxis 0x00 X\nkey 172 MENU\n\nkey nope X\n");
        T.eq("HOME", m.get(172), "first definition wins");
        T.eq("BACK", m.get(158), "flags ignored");
        T.eq("POWER", m.get(0x74), "hex scan code");
        T.eq(3, m.size(), "usage/axis/garbage skipped");
        T.eq(0, KeyLayout.parse(null).size(), "null");
    }
    public void testCandidates() {
        T.eq("[Vendor_0171_Product_0427_Version_0001.kl, Vendor_0171_Product_0427.kl, Amazon_Fire_TV_Remote.kl, Generic.kl]",
            KeyLayout.candidates(0x171, 0x427, 1, "Amazon Fire TV Remote").toString(), "framework order");
        T.eq("[qwerty2.kl, Generic.kl]", KeyLayout.candidates(0, 0, 0, "qwerty2").toString(), "no ids");
    }
}

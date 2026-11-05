import com.clean.stickman.core.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RemotePresetsTest {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    static RemoteLayout.Btn[] en() { return RemoteLayout.forPreset("enhanced"); }

    static int at(RemoteLayout.Btn[] b, int code) {
        int i = RemoteLayout.indexOf(b, code);
        T.yes(i >= 0, "has " + code);
        return i;
    }

    public void testPresetList() {
        String[] ids = { "lite1", "lite2", "gen2", "gen3", "gen2024", "enhanced", "pro", "remote2026" };
        T.eq(ids.length, RemotePresets.ALL.size(), "preset count");
        for (int i = 0; i < ids.length; i++) {
            RemotePresets.Preset p = RemotePresets.ALL.get(i);
            T.eq(ids[i], p.id, "order " + i);
            T.yes(p.name != null && !p.name.isEmpty() && p.years != null && !p.years.isEmpty(), p.id + " named");
            T.yes(p == RemotePresets.get(p.id), p.id + " get");
        }
        T.eq("enhanced", RemotePresets.DEFAULT, "default");
        T.eq("enhanced", RemotePresets.get("nonsense").id, "unknown id falls back");
        T.eq("enhanced", RemotePresets.get(null).id, "null id falls back");
    }

    public void testUniqueSlotsAndCodes() {
        for (RemotePresets.Preset p : RemotePresets.ALL) {
            Set<Integer> codes = new HashSet<>();
            Set<String> slots = new HashSet<>();
            for (RemoteLayout.Btn b : p.buttons) {
                T.yes(slots.add(b.slot), p.id + " duplicate slot " + b.slot);
                if (b.code != 0) T.yes(codes.add(b.code), p.id + " duplicate code " + b.code);
                if (b.code != 0) T.yes(Keys.known(b.code), p.id + " code in Keys.ALL " + b.code);
                T.yes(b.slot.matches("[a-z0-9]+"), p.id + " slot id safe " + b.slot);
            }
        }
    }

    public void testKeysAllIsUnionOfKnownCodes() {
        Set<Integer> union = new HashSet<>();
        for (RemotePresets.Preset p : RemotePresets.ALL) for (RemoteLayout.Btn b : p.buttons) if (b.code != 0) union.add(b.code);
        Set<Integer> all = new HashSet<>();
        for (int k : Keys.ALL) all.add(k);
        T.eq(all, union, "Keys.ALL == union of preset codes");
    }

    public void testNoOverlapIncludingFocusRings() {
        float m = RemoteLayout.focusMargin();
        for (RemotePresets.Preset p : RemotePresets.ALL) {
            RemoteLayout.Btn[] b = p.buttons;
            for (int i = 0; i < b.length; i++) {
                T.yes(b[i].aspect == p.aspect, p.id + " aspect carried");
                T.yes(b[i].cx - b[i].hw() - m >= 0 && b[i].cx + b[i].hw() + m <= 1, p.id + " " + b[i].slot + " inside width");
                float ry = (b[i].hh() + m) / p.aspect;
                T.yes(b[i].cy - ry >= 0 && b[i].cy + ry <= 1, p.id + " " + b[i].slot + " inside height");
                for (int j = i + 1; j < b.length; j++)
                    T.yes(!RemoteLayout.overlaps(b[i], b[j], m), p.id + ": " + b[i].slot + " ring touches " + b[j].slot);
            }
        }
    }

    /** Bodies stay in the designed proportion range, so the body never gets narrower than MIN_BODY_DP. */
    public void testAspectRange() {
        for (RemotePresets.Preset p : RemotePresets.ALL)
            T.yes(p.aspect >= 2.2f && p.aspect <= RemoteLayout.MAX_ASPECT, p.id + " aspect " + p.aspect);
    }

    public void testEveryButtonReachableFromOk() {
        for (RemotePresets.Preset p : RemotePresets.ALL) {
            RemoteLayout.Btn[] b = p.buttons;
            int ok = at(b, 96);
            Set<Integer> seen = new HashSet<>();
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(ok); seen.add(ok);
            while (!q.isEmpty()) {
                int c = q.poll();
                for (int d = 0; d < 4; d++) { int n = RemoteLayout.next(b, c, d); if (seen.add(n)) q.add(n); }
            }
            T.eq(b.length, seen.size(), p.id + " reachable from OK");
        }
    }

    public void testDpadNeighboursEveryPreset() {
        for (RemotePresets.Preset p : RemotePresets.ALL) {
            RemoteLayout.Btn[] b = p.buttons;
            int ok = at(b, 96);
            T.eq(at(b, 103), RemoteLayout.next(b, ok, UP), p.id + " OK up");
            T.eq(at(b, 108), RemoteLayout.next(b, ok, DOWN), p.id + " OK down");
            T.eq(at(b, 105), RemoteLayout.next(b, ok, LEFT), p.id + " OK left");
            T.eq(at(b, 106), RemoteLayout.next(b, ok, RIGHT), p.id + " OK right");
        }
    }

    /** The owner's UK Enhanced remote: row 1 Prime Video, Netflix; row 2 Apps, Amazon Music. */
    public void testEnhancedAppOrder() {
        RemoteLayout.Btn[] b = en();
        RemoteLayout.Btn prime = b[at(b, 745)], netflix = b[at(b, 744)], apps = b[at(b, 746)], music = b[at(b, 747)];
        T.yes(prime.cy == netflix.cy && prime.cx < netflix.cx, "745 left of 744 on one row");
        T.yes(apps.cy == music.cy && apps.cx < music.cx, "746 left of 747 on one row");
        T.yes(prime.cy < apps.cy, "Prime/Netflix row above Apps/Music");
        T.eq("Prime Video", prime.name, "745 label");
        T.eq("Netflix", netflix.name, "744 label");
        T.eq("Apps", apps.name, "746 label");
        T.eq("Amazon Music", music.name, "747 label");
        T.eq(at(b, 744), RemoteLayout.next(b, at(b, 745), RIGHT), "Prime right -> Netflix");
        T.eq(at(b, 746), RemoteLayout.next(b, at(b, 745), DOWN), "Prime down -> Apps");
        T.eq(at(b, 747), RemoteLayout.next(b, at(b, 744), DOWN), "Netflix down -> Music");
        T.eq(at(b, 746), RemoteLayout.next(b, at(b, 747), LEFT), "Music left -> Apps");
    }

    /** Enhanced matches the photo: Live TV between the rockers, then Mute | Settings | Recents. */
    public void testEnhancedMiddleRows() {
        RemoteLayout.Btn[] b = en();
        RemoteLayout.Btn mute = b[at(b, 113)], set = b[at(b, 249)], rec = b[at(b, 748)], tv = b[at(b, 362)];
        T.yes(mute.cy == set.cy && set.cy == rec.cy && mute.cx < set.cx && set.cx < rec.cx, "Mute | Settings | Recents");
        T.yes(b[at(b, 115)].cx < tv.cx && tv.cx < b[at(b, 402)].cx, "Live TV between the rockers");
        T.eq(2, RemotePresets.get("enhanced").rockers.length, "two rockers");
        T.eq(25, b.length, "25 buttons");
    }

    public void testUnknownButtonsHaveCodeZero() {
        String[][] exp = { { "pro", "headphones", "custom1", "custom2" }, { "remote2026", "mic", "star", "sliders" } };
        for (String[] e : exp) {
            RemotePresets.Preset p = RemotePresets.get(e[0]);
            Set<String> zero = new HashSet<>();
            for (RemoteLayout.Btn b : p.buttons) if (b.code == 0) zero.add(b.slot);
            T.eq(e.length - 1, zero.size(), e[0] + " unknown count");
            for (int i = 1; i < e.length; i++) T.yes(zero.contains(e[i]), e[0] + " has unlearned " + e[i]);
        }
        for (String id : new String[] { "lite1", "lite2", "gen2", "gen3", "gen2024", "enhanced" })
            for (RemoteLayout.Btn b : RemotePresets.get(id).buttons) T.yes(b.code != 0, id + " all known " + b.slot);
    }

    public void testPresetShapes() {
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("lite1").buttons, 116), "Lite 1 has no power");
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("lite1").buttons, 745), "Lite 1 has no apps");
        T.yes(RemoteLayout.indexOf(RemotePresets.get("lite2").buttons, 745) >= 0, "Lite 2 has apps");
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("gen2").buttons, 362), "gen2 no guide");
        T.eq(1, RemotePresets.get("gen2").rockers.length, "gen2 one rocker");
        T.eq(1, RemotePresets.get("gen3").rockers.length, "gen3 one rocker");
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("pro").buttons, 748), "Pro has no Recents");
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("remote2026").buttons, 168), "2026 has no Rewind");
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("remote2026").buttons, 208), "2026 has no FF");
        T.eq(4, RemotePresets.appSlots(RemotePresets.get("remote2026")).size(), "2026 four app buttons");
    }

    public void testResolveAppliesLearnedAndLabels() {
        RemotePresets.Preset p = RemotePresets.get("pro");
        Map<String, Integer> learned = new HashMap<>();
        learned.put("custom1", 600);
        learned.put("custom2", 96);        // collides with OK: ignored
        learned.put("nosuchslot", 601);
        learned.put("k116", 602);          // only code-0 slots can be learned
        Map<String, String> labels = new HashMap<>();
        labels.put("app3", "Apps");
        labels.put("app4", "bad\nlabel");  // invalid: ignored
        labels.put("k116", "Not an app");  // only app slots can be renamed
        RemoteLayout.Btn[] b = RemotePresets.resolve(p, learned, labels);
        T.eq(p.buttons.length, b.length, "same count");
        T.eq(600, b[RemotePresets.slotIndex(b, "custom1")].code, "custom1 learned");
        T.eq(0, b[RemotePresets.slotIndex(b, "custom2")].code, "custom2 collision ignored");
        T.eq(116, b[RemotePresets.slotIndex(b, "k116")].code, "power keeps its code");
        T.eq("Apps", b[RemotePresets.slotIndex(b, "app3")].name, "renamed");
        T.eq(p.buttons[RemotePresets.slotIndex(b, "app4")].name, b[RemotePresets.slotIndex(b, "app4")].name, "bad rename ignored");
        T.eq("Power", b[RemotePresets.slotIndex(b, "k116")].name, "non-app not renamed");
        T.eq(0, p.buttons[RemotePresets.slotIndex(b, "custom1")].code, "preset itself untouched");
        T.eq(2, RemotePresets.unlearned(b).size(), "headphones + custom2 still unlearned");
        T.eq(-1, RemotePresets.slotIndex(b, "nope"), "missing slot");
    }

    public void testValidLabel() {
        T.eq(null, RemotePresets.invalidLabel("Disney+"), "ok");
        T.eq(null, RemotePresets.invalidLabel("123456789012345678901234"), "24 ok");
        T.yes(RemotePresets.invalidLabel("1234567890123456789012345") != null, "25 too long");
        T.yes(RemotePresets.invalidLabel("a\nb") != null, "newline");
        T.yes(RemotePresets.invalidLabel("a\rb") != null, "cr");
        T.yes(RemotePresets.invalidLabel("   ") != null, "blank");
        T.yes(RemotePresets.invalidLabel(null) != null, "null");
        T.yes(RemotePresets.invalidLabel("a\tb") != null, "control char");
    }

    public void testHonestNames() {
        for (RemotePresets.Preset p : RemotePresets.ALL)
            T.eq(p.approximate, p.name.contains("(layout approximate)"), p.id + " marks approximation");
        T.yes(!RemotePresets.get("enhanced").approximate, "enhanced is exact (owner's remote)");
        T.yes(RemotePresets.get("remote2026").approximate, "2026 approximate");
    }

    public void testDisplayName() {
        RemoteLayout.Btn[] b = en();
        T.eq("Volume up", b[at(b, 115)].name, "known keys use Keys.name");
        T.eq("Custom button 1", RemotePresets.get("pro").buttons[RemotePresets.slotIndex(RemotePresets.get("pro").buttons, "custom1")].name, "unknown named");
    }
}

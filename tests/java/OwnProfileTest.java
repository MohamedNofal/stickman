import com.clean.stickman.core.*;

public class OwnProfileTest {
    static final String P = "com.clean.stickman";

    public void testAddsTapOrigForRemappedPlayPause() {
        RemapModel m = RemapModel.parse("profile default\n164 double act boost\n172 tap start a.b/.C\n");
        T.yes(OwnProfile.ensure(m), "changed");
        T.yes(m.profiles().contains(P), "profile added");
        T.eq("orig", m.get(P, 164, Rule.Layer.TAP), "164 tap orig");
        T.eq(null, m.get(P, 164, Rule.Layer.DOUBLE), "only tap");
        T.yes(!m.hasRules(P, 139), "139 untouched (not remapped)");
        T.yes(!m.hasRules(P, 172), "other keys untouched");
        T.eq("act boost", m.get("default", 164, Rule.Layer.DOUBLE), "default kept");
    }

    public void testBothKeys() {
        RemapModel m = RemapModel.parse("profile default\n164 hold act boost\n139 tap key 4\n");
        T.yes(OwnProfile.ensure(m), "changed");
        T.eq("orig", m.get(P, 164, Rule.Layer.TAP), "164");
        T.eq("orig", m.get(P, 139, Rule.Layer.TAP), "139");
    }

    public void testNothingOnTheKeysUnchanged() {
        RemapModel m = RemapModel.parse("profile default\n172 tap start a.b/.C\n");
        String before = m.serialize();
        T.yes(!OwnProfile.ensure(m), "false");
        T.eq(before, m.serialize(), "unchanged");
        T.yes(!m.profiles().contains(P), "no profile");
    }

    public void testOtherProfilesDontCount() {
        RemapModel m = RemapModel.parse("profile default\n172 tap none\nprofile org.xbmc.kodi\n164 tap key 85\n");
        T.yes(!OwnProfile.ensure(m), "only the default profile counts");
    }

    public void testProfileAlreadyPresentUnchanged() {
        RemapModel m = RemapModel.parse("profile default\n164 double act boost\nprofile " + P + "\n164 tap orig\n");
        String before = m.serialize();
        T.yes(!OwnProfile.ensure(m), "false");
        T.eq(before, m.serialize(), "unchanged");
    }

    public void testOwnerEditedProfileRespected() {
        // the owner deliberately set something else for 164 inside Stickman: leave it
        RemapModel m = RemapModel.parse("profile default\n164 double act boost\nprofile " + P + "\n164 hold none\n");
        String before = m.serialize();
        T.yes(!OwnProfile.ensure(m), "false");
        T.eq(before, m.serialize(), "unchanged");
    }

    public void testIdempotent() {
        RemapModel m = RemapModel.parse("profile default\n139 double act boost\n");
        T.yes(OwnProfile.ensure(m), "first");
        T.yes(!OwnProfile.ensure(m), "second is a no-op");
        T.eq("orig", m.get(P, 139, Rule.Layer.TAP), "139");
    }

    /** M7: an existing Stickman profile that lacks one of the codes gets it, the owner's entries stay. */
    public void testExistingProfileCompletedForMissingCode() {
        RemapModel m = RemapModel.parse("profile default\n164 double act boost\n139 hold act boost\nprofile " + P + "\n164 hold none\n");
        T.yes(OwnProfile.ensure(m), "changed");
        T.eq("none", m.get(P, 164, Rule.Layer.HOLD), "owner's 164 kept");
        T.eq(null, m.get(P, 164, Rule.Layer.TAP), "164 not touched (owner defined it)");
        T.eq("orig", m.get(P, 139, Rule.Layer.TAP), "139 added: the owner remapped Menu globally later");
        T.yes(!OwnProfile.ensure(m), "then a no-op");
    }
}

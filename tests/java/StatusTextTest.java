import com.clean.stickman.core.StatusText;

public class StatusTextTest {
    /** Labels for the packages the daemon logs; unknown packages come back as themselves. */
    static final StatusText.Label L = pkg -> {
        switch (pkg) {
            case "com.netflix.ninja": return "Netflix";
            case "com.amazon.avod": return "Prime Video";
            case "app.wako": return "wako";
            default: return pkg;
        }
    };

    public void testAppInstallerLines() {
        T.eq("Installed wako.", StatusText.human("STATUS app:app.wako state=installed pkg=app.wako vc=12", L), "installed");
        T.eq("Uninstalled wako.", StatusText.human("STATUS app:app.wako state=removed pkg=app.wako", L), "removed");
        T.eq("wako: Not enough free storage: needs about 300 MB, 120 MB free.",
            StatusText.human("STATUS app:app.wako state=error msg=Not enough free storage: needs about 300 MB, 120 MB free.", L), "what went wrong, as said");
        T.eq("Add an app: No app behind code 12345.", StatusText.human("STATUS app:link state=error msg=No app behind code 12345.", L), "a typed code that led nowhere");
        T.eq(null, StatusText.human("STATUS app:app.wako state=downloading pct=43 size=1000", L), "progress is not news");
        T.eq(null, StatusText.human("STATUS app:app.wako state=queued", L), "nor is waiting");
        T.eq(null, StatusText.human("STATUS app:link state=confirm pkg=app.wako vc=12 size=1000 msg=installed=-", L), "nor the question");
        T.eq(null, StatusText.human("STATUS app:link state=installed pkg=app.wako vc=12", L), "the package's own line says it");
        T.eq(null, StatusText.human("STATUS apps list=app.wako:1:catalog:0", L), "the tracked list is plumbing");
        T.eq(null, StatusText.human("STATUS apps list=", L), "even when empty");
    }

    public void testDaemonLines() {
        T.eq("Stickman's background helper started.", StatusText.human("STATUS started version=10", L), "started");
        T.eq("In front: Netflix.", StatusText.human("STATUS fg com.netflix.ninja profile=default", L), "fg default");
        T.eq("In front: Netflix (its own buttons).", StatusText.human("STATUS fg com.netflix.ninja profile=com.netflix.ninja", L), "fg own profile");
        T.eq("Boost: freed 120 MB, closed 3 apps.", StatusText.human("STATUS boost freed_mb=120 killed=3", L), "boost");
        T.eq("Boost: freed 0 MB, closed 1 app.", StatusText.human("boost freed_mb=0 killed=1", L), "boost singular, no prefix");
        T.eq("Trim: 12 apps gave back 85 MB, nothing closed.", StatusText.human("STATUS trim freed_mb=85 trimmed=12 why=now", L), "trim now");
        T.eq("Trim as the TV went to sleep: 12 apps gave back 85 MB, nothing closed.",
            StatusText.human("STATUS trim freed_mb=85 trimmed=12 why=sleep", L), "trim sleep");
        T.eq("Timed trim: 1 app gave back 5 MB, nothing closed.", StatusText.human("STATUS trim freed_mb=5 trimmed=1 why=interval", L), "trim interval");
        T.eq("Auto-closed: Netflix, wako.", StatusText.human("STATUS autokill killed=com.netflix.ninja,app.wako", L), "autokill");
        T.eq("Sleep timer off.", StatusText.human("STATUS sleep_timer off", L), "timer off");
        T.eq("Sleep timer: 30 min.", StatusText.human("STATUS sleep_timer 30", L), "timer set");
        T.eq("Private DNS check: the internet works ✓", StatusText.human("STATUS dns ok", L), "dns ok");
        T.eq("Private DNS switched off: the internet stopped working.", StatusText.human("STATUS dns reverted", L), "dns reverted");
        T.eq("Ended Prime Video.", StatusText.human("STATUS end com.amazon.avod ok", L), "end ok");
        T.eq("Couldn't end Prime Video: always protected.", StatusText.human("STATUS end com.amazon.avod refused=never", L), "end refused");
        T.eq("End all: closed 4 apps.", StatusText.human("STATUS end_all killed=4", L), "end all");
        T.eq("Freed 150 MB from 3 apps.", StatusText.human("STATUS freed FREED 153600 3", L), "freed");
        T.eq("Free up space: nothing to free.", StatusText.human("STATUS freed FREED 0 0", L), "nothing freed");
        T.eq("Cleared 164 MB of app caches.", StatusText.human("STATUS cleared CLEARED 167936", L), "cleared");
    }

    public void testHousekeepingHidden() {
        T.eq(null, StatusText.human("STATUS tasks total=1700000 avail=800000 cpu=12 seq=3", L), "feed header");
        T.eq(null, StatusText.human("STATUS task com.x rss=1 cpu=0 state=bg up=1 procs=1 seq=3", L), "feed line");
        T.eq(null, StatusText.human("STATUS tasks_end seq=3", L), "feed end");
        T.eq(null, StatusText.human("STATUS tasks stopped", L), "sampling stopped");
        T.eq(null, StatusText.human("", L), "empty");
        T.eq(null, StatusText.human(null, L), "null");
    }

    public void testUnknownShownAsIs() {
        T.eq("something new here", StatusText.human("STATUS something new here", L), "unknown kind");
        T.eq("In front: com.unknown.app.", StatusText.human("STATUS fg com.unknown.app profile=default", null), "no label source");
        T.eq("Boost: freed ? MB, closed ? apps.", StatusText.human("STATUS boost", L), "missing fields never crash");
        T.eq("Auto-close: nothing to close.", StatusText.human("STATUS autokill killed=", L), "empty kill list");
    }
}

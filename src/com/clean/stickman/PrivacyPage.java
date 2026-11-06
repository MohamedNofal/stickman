package com.clean.stickman;

import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Telemetry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private-DNS ad blocking presets, a status card, the "limit ads & tracking" switch, Quiet telemetry (appops on
 * Amazon's reporting apps that Fire OS won't let anyone disable) and the location switch.
 */
public class PrivacyPage extends ApplyPage {
    static final long AUTO_READ_MS = 30000;
    /** Prints one line "DNS <mode> <specifier> ADS <limit_ad_tracking> QUIET <n> LOC <location_mode>". */
    static final String READ = "echo DNS $(settings get global private_dns_mode) $(settings get global private_dns_specifier)"
        + " ADS $(settings get secure limit_ad_tracking) QUIET " + Telemetry.COUNT_EXPR + " LOC $(settings get secure location_mode)";
    static final Pattern STATE = Pattern.compile("DNS (\\S+) (\\S+) ADS (\\S+)(?: QUIET (\\d+))?(?: LOC (\\S+))?");

    Button[] presets, ads, quiet, loc;
    TextView dnsNow, dnsCheck;
    long readAt, checkShownAt = -1;

    @Override public String title() { return "Privacy"; }

    @Override public String subtitle() { return "Block ads and trackers in every app, and keep Amazon's reporting quiet."; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);

        LinearLayout s = card(l, "Right now", null);
        dnsNow = Theme.text(a, "Not read yet: press Refresh.", 16, Theme.TEXT);
        s.addView(dnsNow);
        dnsCheck = Theme.text(a, "", 14, Theme.MUTED);
        s.addView(dnsCheck);
        button(s, "Refresh", v -> read());

        LinearLayout d = card(l, "Ad-blocking DNS", "Blocks ads and trackers in every app through an ad-blocking DNS "
            + "service. If the internet stops working after a change, Stickman switches it back off by itself.");
        String[] names = new String[Catalog.DNS_PRESETS.length];
        for (int i = 0; i < names.length; i++) names[i] = Catalog.DNS_PRESETS[i][0];
        LinearLayout r1 = Theme.row(a), r2 = Theme.row(a);
        r1.setTag(Theme.CHIPS);
        r2.setTag(Theme.CHIPS);
        presets = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            presets[i] = button(i < 3 ? r1 : r2, names[i], v -> choose(idx));
        }
        d.addView(r1);
        d.addView(r2);
        d.addView(Theme.text(a, "Works alongside Net Filter. AdGuard blocks Amazon's metrics hosts; NextDNS with its "
            + "Amazon list also blocks the event and impression hosts that Amazon's built-in reporting services use.", 14, Theme.MUTED));

        LinearLayout p = card(l, "Limit ads & tracking", "Turns off the advertising ID, interest-based ads, device and "
            + "app usage data, marketing metrics and autoplaying featured videos.");
        ads = choices(p, new String[] { "On", "Off" }, i ->
            send(Commands.privacy(i == 0) + "; " + READ, "ads:" + i, "Applying…"));

        LinearLayout q = card(l, "Amazon's reporting apps", "Fire OS won't let these eight be disabled: "
            + Telemetry.summary() + ". Quiet stops all of them running in the background and takes app-usage access "
            + "from the statistics one. Nothing you use depends on them; Restore defaults puts them back. Amazon's "
            + "built-in Minerva services can't be stopped this way: only an ad-blocking DNS reaches those.");
        quiet = choices(q, new String[] { "Quiet", "Let them run" }, i ->
            send(Telemetry.quiet(i == 0) + "; " + READ, "quiet:" + i,
                i == 0 ? "Quieting Amazon's reporting apps…" : "Letting them run again…"));

        LinearLayout lc = card(l, "Location", "The stick looks up a Wi-Fi based location for weather, ads and "
            + "regional content. Off stops those lookups; everything else keeps working.");
        loc = choices(lc, new String[] { "On", "Off" }, i ->
            send(Commands.location(i == 0) + "; " + READ, "loc:" + i, i == 0 ? "Turning location on…" : "Turning location off…"));

        return Theme.scroll(a, l);
    }

    void read() {
        readAt = System.currentTimeMillis();
        send(READ, "read", "Reading…");
    }

    void choose(int i) {
        String[] pr = Catalog.DNS_PRESETS[i];
        if (pr[1] == null) { apply(null, pr[0]); return; }
        if (!"nextdns".equals(pr[1])) { apply(pr[1], pr[0]); return; }
        final EditText id = Theme.edit(a);
        id.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        id.setHint("e.g. abc123");
        new ThemedDialog(a).title("Your NextDNS ID").message("The ID from your NextDNS setup page (letters and numbers).")
            .view(id)
            .button("Use it", () -> {
                String host = Catalog.nextDns(id.getText().toString().trim());
                if (host == null) { say("That isn't a valid NextDNS ID: nothing changed."); return; }
                apply(host, "NextDNS");
            })
            .cancel("Cancel").focus(id).show();
    }

    void apply(String host, String label) {
        String cmd = Commands.privateDns(host);
        if (cmd == null) { say("Invalid DNS host: nothing changed."); return; }
        send(cmd + "; " + READ, "dns:" + label, host == null ? "Turning private DNS off…" : "Switching to " + label + "…");
    }

    @Override public void onShow() {
        if (a == null) return;
        if (System.currentTimeMillis() - readAt > AUTO_READ_MS && !Bridge.otherVpnActive(a)) read();
        onTick();
    }

    @Override public void onTick() {
        if (a == null) return;
        long at = LogTail.statusAt("dns");
        if (at == checkShownAt) return;
        checkShownAt = at;
        String st = LogTail.status("dns");
        if (st == null) { dnsCheck.setText("Safety check: no result yet."); return; }
        String when = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(at));
        dnsCheck.setText("Safety check " + when + ": " + ("ok".equals(st.trim()) ? "internet works ✓"
            : "reverted".equals(st.trim()) ? "internet failed, so DNS was switched off" : st));
    }

    /** Updates the status card + selections from a READ line; returns the DNS description. */
    String show(String out) {
        Matcher m = STATE.matcher(out == null ? "" : out);
        String last = null;
        while (m.find()) last = m.group(0);
        if (last == null) return null;
        m = STATE.matcher(last);
        m.find();
        String mode = m.group(1), spec = m.group(2), lim = m.group(3);
        int n = m.group(4) == null ? -1 : Integer.parseInt(m.group(4));
        String locMode = m.group(5);
        boolean on = "hostname".equals(mode) && !"null".equals(spec);
        String desc = on ? spec : "Off (" + ("null".equals(mode) ? "default" : mode) + ")";
        int sel = -1;
        for (int i = 0; i < Catalog.DNS_PRESETS.length; i++) {
            String h = Catalog.DNS_PRESETS[i][1];
            if (on ? (h != null && (h.equals(spec) || ("nextdns".equals(h) && spec.endsWith(".dns.nextdns.io")))) : h == null) sel = i;
            if (on && h != null && h.equals(spec)) desc = Catalog.DNS_PRESETS[i][0] + "  (" + spec + ")";
        }
        if (on && spec.endsWith(".dns.nextdns.io")) desc = "NextDNS  (" + spec + ")";
        boolean locOn = locMode == null || !"0".equals(locMode);
        dnsNow.setText("Ad-blocking DNS: " + desc + "\nLimit ads & tracking: " + ("1".equals(lim) ? "On" : "Off")
            + "\nAmazon's reporting apps: " + Telemetry.state(n)
            + "\nLocation: " + (locMode == null ? "not read yet" : locOn ? "On" : "Off"));
        select(presets, sel);
        select(ads, "1".equals(lim) ? 0 : 1);
        select(quiet, n < 0 ? -1 : n >= Telemetry.APPS.length ? 0 : n == 0 ? 1 : -1);
        select(loc, locMode == null ? -1 : locOn ? 0 : 1);
        return desc;
    }

    @Override String describe(String kind, String out) {
        String desc = show(out);
        if (kind.equals("read")) { result.setText(""); return null; }
        if (kind.startsWith("dns:")) {
            if (out != null && out.contains(Commands.DNS_REFUSED))
                return "Background automation is off, so DNS was not changed (its internet safety check needs it). "
                    + "Turn Stickman on, then try again.";
            if (desc == null) return "Private DNS: " + kind.substring(4) + " applied.";
            return desc.startsWith("Off") ? "Private DNS is off." : "Private DNS: " + desc + ". Checking the internet still works…";
        }
        if (kind.startsWith("ads:")) return "Limit ads & tracking " + (kind.endsWith("0") ? "on." : "off.");
        if (kind.startsWith("quiet:")) {
            int n = Telemetry.count(out);
            if (kind.endsWith("0")) return n >= Telemetry.APPS.length ? "Amazon's reporting apps are quiet."
                : "Only " + Math.max(n, 0) + " of " + Telemetry.APPS.length + " could be quieted: press Refresh and try again.";
            return n == 0 ? "Amazon's reporting apps run normally again." : "Some are still quiet: press Refresh and try again.";
        }
        if (kind.startsWith("loc:")) return kind.endsWith("0") ? "Location on." : "Location off.";
        return out;
    }
}

package dev.supermux.desktop.packaging;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version the macOS bundle and the Linux .deb show, from the app's semver (#139; used by
 * apps/desktop/build.gradle.kts, tested by BundleVersionTest). Every desktop build used to say 1.0.0
 * there: jpackage refuses a 0.x app version on macOS, so Compose's packageVersion was fixed.
 * <ul>
 *   <li>macOS CFBundleShortVersionString (what Get Info and About show): Apple's numeric
 *       {@code X.Y.Z}, so a prerelease shows its release's number. CFBundleVersion is
 *       {@link MsiVersion}: numeric, monotonic, and above the 1.0.0 every older copy carries.</li>
 *   <li>Debian: {@code 1:X.Y.Z}, a prerelease {@code 1:X.Y.Z~label.N} ({@code ~} sorts before the
 *       release). Epoch 1 keeps it above the 1.0.0 already installed, so dpkg and apt upgrade.</li>
 *   <li>{@code dev} (a local build): null, the package keeps jpackage's 1.0.0.</li>
 * </ul>
 * The in-app updater never reads these: it compares DESKTOP_APP_VERSION, the semver itself.
 */
public final class BundleVersion {
    private BundleVersion() {}

    private static final Pattern SEMVER =
        Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?$");

    private static Matcher parse(String version) {
        if (version == null || version.isBlank() || version.equals("dev")) return null;
        Matcher m = SEMVER.matcher(version.trim());
        if (!m.matches()) throw new IllegalArgumentException("not a semver version: " + version);
        return m;
    }

    /** CFBundleShortVersionString: {@code X.Y.Z}, null for a dev build. */
    public static String macShortVersion(String version) {
        Matcher m = parse(version);
        if (m == null) return null;
        return Integer.parseInt(m.group(1)) + "." + Integer.parseInt(m.group(2)) + "." + Integer.parseInt(m.group(3));
    }

    /** CFBundleVersion: {@link MsiVersion}, null for a dev build (whose 0.0.1 jpackage would refuse). */
    public static String macBuildVersion(String version) {
        return parse(version) == null ? null : MsiVersion.of(version);
    }

    /** The .deb's upstream version with epoch, without the Debian revision; null for a dev build. */
    public static String debVersion(String version) {
        Matcher m = parse(version);
        if (m == null) return null;
        String upstream = macShortVersion(version);
        String pre = m.group(4);
        // A '-' in the upstream part would read as the revision separator.
        return "1:" + upstream + (pre == null ? "" : "~" + pre.replace('-', '.'));
    }

    /**
     * [control] (a DEBIAN/control file) with its {@code Version:} set to [upstream] plus the
     * revision it had ({@code 1.0.0-1} → {@code <upstream>-1}). Throws when there is no Version.
     */
    public static String withDebVersion(String control, String upstream) {
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (String line : control.split("\n", -1)) {
            if (line.startsWith("Version:")) {
                String old = line.substring("Version:".length()).trim();
                int dash = old.lastIndexOf('-');
                out.add("Version: " + upstream + (dash >= 0 ? old.substring(dash) : ""));
                found = true;
            } else {
                out.add(line);
            }
        }
        if (!found) throw new IllegalArgumentException("DEBIAN/control has no Version field");
        return String.join("\n", out);
    }
}

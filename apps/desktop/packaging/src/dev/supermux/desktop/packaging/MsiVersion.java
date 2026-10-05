package dev.supermux.desktop.packaging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Windows MSI's ProductVersion from the app's semver (used by apps/desktop/build.gradle.kts,
 * tested by MsiVersionTest).
 *
 * Windows Installer only upgrades in place (MajorUpgrade) when the new ProductVersion is higher in
 * its first three fields, each at most 255.255.65535, and a prerelease suffix is not allowed. Every
 * MSI used to say 1.0.0, so a newer one refused to install ("another version is already
 * installed"). The mapping is deterministic and monotonic:
 * <ul>
 *   <li>release {@code X.Y.Z} → {@code X.Y.(Z*1000 + 999)}</li>
 *   <li>{@code X.Y.Z-alpha.N} → {@code X.Y.(Z*1000 + N)}, N 0–299 (other labels, e.g. test.N, rank as alpha)</li>
 *   <li>{@code X.Y.Z-beta.N} → {@code X.Y.(Z*1000 + 300 + N)}, N 0–299</li>
 *   <li>{@code X.Y.Z-rc.N} → {@code X.Y.(Z*1000 + 600 + N)}, N 0–398</li>
 *   <li>{@code dev} (a local build) → {@code 0.0.1}</li>
 * </ul>
 * so alpha &lt; beta &lt; rc &lt; release within one X.Y.Z, and any X.Y.Z+1 is above all of X.Y.Z.
 */
public final class MsiVersion {
    private MsiVersion() {}

    private static final Pattern SEMVER =
        Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+)(?:\\.(\\d+))?)?(?:\\+.*)?$");

    public static String of(String version) {
        if (version == null || version.isBlank() || version.equals("dev")) return "0.0.1";
        Matcher m = SEMVER.matcher(version.trim());
        if (!m.matches()) throw new IllegalArgumentException("not a semver version: " + version);
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = Integer.parseInt(m.group(3));
        String label = m.group(4);
        int n = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int offset;
        if (label == null) {
            offset = 999;
        } else if (label.equals("rc")) {
            offset = 600 + bounded(n, 398, version);
        } else if (label.equals("beta")) {
            offset = 300 + bounded(n, 299, version);
        } else {
            offset = bounded(n, 299, version);
        }
        if (major > 255 || minor > 255) throw new IllegalArgumentException("MSI major/minor must be ≤ 255: " + version);
        long build = (long) patch * 1000 + offset;
        if (build > 65535) throw new IllegalArgumentException("MSI build field must be ≤ 65535 (patch ≤ 64): " + version);
        return major + "." + minor + "." + build;
    }

    private static int bounded(int n, int max, String version) {
        if (n > max) throw new IllegalArgumentException("prerelease number above " + max + ": " + version);
        return n;
    }
}

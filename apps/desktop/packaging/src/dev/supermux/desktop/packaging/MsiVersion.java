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
 *   <li>release {@code X.Y.Z} → {@code (X+1).Y.(Z*100 + 99)}</li>
 *   <li>{@code X.Y.Z-alpha.N} → {@code (X+1).Y.(Z*100 + N)}, N 0–29 (other labels, e.g. test.N, rank as alpha)</li>
 *   <li>{@code X.Y.Z-beta.N} → {@code (X+1).Y.(Z*100 + 30 + N)}, N 0–29</li>
 *   <li>{@code X.Y.Z-rc.N} → {@code (X+1).Y.(Z*100 + 60 + N)}, N 0–38</li>
 *   <li>{@code dev} (a local build) → {@code 0.0.1}</li>
 * </ul>
 * so alpha &lt; beta &lt; rc &lt; release within one X.Y.Z, and any X.Y.Z+1 is above all of X.Y.Z.
 *
 * Major + 1: every MSI shipped before this said 1.0.0, and the releases are 0.x. An upgrade must be
 * a HIGHER version, so 0.12.0 maps to 1.12.99 (above 1.0.0), and those installs upgrade in place.
 * Patch × 100 (not × 1000) keeps patch numbers up to 655 inside MSI's 65535 (0.11 reached .36).
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
            offset = 99;
        } else if (label.equals("rc")) {
            offset = 60 + bounded(n, 38, version);
        } else if (label.equals("beta")) {
            offset = 30 + bounded(n, 29, version);
        } else {
            offset = bounded(n, 29, version);
        }
        int msiMajor = major + 1;
        if (msiMajor > 255 || minor > 255) throw new IllegalArgumentException("MSI major/minor must be ≤ 255: " + version);
        long build = (long) patch * 100 + offset;
        if (build > 65535) throw new IllegalArgumentException("MSI build field must be ≤ 65535 (patch ≤ 655): " + version);
        return msiMajor + "." + minor + "." + build;
    }

    private static int bounded(int n, int max, String version) {
        if (n > max) throw new IllegalArgumentException("prerelease number above " + max + ": " + version);
        return n;
    }
}

package dev.supermux.desktop.packaging;

import java.util.ArrayList;
import java.util.List;

/**
 * Text edits on a built .deb's launcher entry (used by {@code addStartupWmClassToDebs} in
 * apps/desktop/build.gradle.kts, tested by DebLauncherEntryTest).
 *
 * jpackage writes {@code supermux-supermux.desktop} without {@code StartupWMClass}, so GNOME
 * cannot tie the app's "supermux" window (WM_CLASS) to its launcher. These add the line right
 * after {@code Type=}, idempotently, and keep {@code DEBIAN/md5sums} true to the edited file.
 */
public final class DebLauncherEntry {
    private DebLauncherEntry() {}

    /** True when [desktop] already names a {@code StartupWMClass}. */
    public static boolean hasStartupWmClass(String desktop) {
        for (String line : desktop.split("\n", -1)) {
            if (line.startsWith("StartupWMClass=")) return true;
        }
        return false;
    }

    /**
     * [desktop] with {@code StartupWMClass=[wmClass]} after its {@code Type=} line (at the end when
     * there is none). Unchanged when it already has a StartupWMClass. Always ends with a newline.
     */
    public static String withStartupWmClass(String desktop, String wmClass) {
        if (hasStartupWmClass(desktop)) return desktop;
        List<String> lines = lines(desktop);
        int at = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Type=")) {
                at = i + 1;
                break;
            }
        }
        lines.add(at, "StartupWMClass=" + wmClass);
        return String.join("\n", lines) + "\n";
    }

    /**
     * [md5sums] (dpkg's "{@code <md5>  <path>}" lines) with [path]'s sum replaced by [md5]. Other
     * lines are kept; a path that is not listed leaves it unchanged.
     */
    public static String withMd5(String md5sums, String path, String md5) {
        List<String> out = new ArrayList<>();
        for (String line : lines(md5sums)) {
            int sep = line.indexOf("  ");
            out.add(sep >= 0 && line.substring(sep + 2).equals(path) ? md5 + "  " + path : line);
        }
        return out.isEmpty() ? md5sums : String.join("\n", out) + "\n";
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        // A trailing newline leaves one empty element; it is re-added on join.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }
}

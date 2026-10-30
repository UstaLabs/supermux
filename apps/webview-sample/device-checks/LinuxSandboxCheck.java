import dev.supermux.webview.engine.linux.WpeNative;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Linux engine's glue on a WPE runtime with WebKit's page sandbox on, in a machine's own session (no container):
 * the engine's natives (WpeNative, from webview-compose's jar) on the runtime's glue, as WpeRuntime starts them, then
 * one page. It passes when the page's colour is in a frame WebKit rendered and its web process is in a user namespace
 * of its own (bubblewrap's). Run by linux-sandbox-check.sh: java LinuxSandboxCheck.java RUNTIME_DIR [off], where
 * off runs the same page with the sandbox off, for comparison.
 * Exit status: 0 passed, 1 failed; WebKit aborting the JVM (what C1 did) is 134.
 */
public class LinuxSandboxCheck {
    private static final int COLOR = 0x3366cc;

    public static void main(String[] args) throws Exception {
        File runtime = new File(args[0]).getCanonicalFile();
        boolean sandbox = args.length < 2 || !args[1].equals("off");
        File work = Files.createTempDirectory("wpe-sandbox-check").toFile();
        System.load(new File(runtime, "lib/libwebview-wpe.so").getPath());
        WpeNative.setenv("WEBKIT_EXEC_PATH", new File(runtime, "libexec").getPath());
        WpeNative.setenv("WEBKIT_INJECTED_BUNDLE_PATH", new File(runtime, "lib/wpe-webkit-2.0/injected-bundle").getPath());
        AtomicBoolean finished = new AtomicBoolean();
        WpeNative.INSTANCE.setSink((view, kind, a, b, s1, s2) -> {
            // The glue holds every policy decision for the engine: this page is the check's own load, allowed.
            if (kind == WpeNative.POLICY || kind == WpeNative.RESPONSE) WpeNative.decide(view, a, WpeNative.USE);
            if (kind == WpeNative.LOAD && a == WpeNative.LOAD_FINISHED) finished.set(true);
            if (kind == WpeNative.CRASHED) System.out.println("SANDBOX the web process ended: reason " + a);
        });
        int started = WpeNative.init(sandbox ? 0 : WpeNative.INIT_NO_SANDBOX, new File(work, "data").getPath(), new File(work, "cache").getPath(), runtime.getPath());
        if (started != 0) fail("WebKit didn't start");
        long view = WpeNative.createView(WpeNative.PROFILE_EPHEMERAL, null, false, 320, 240, 1.0);
        if (view <= 0) fail("no view");
        WpeNative.frameMode(view, true, false, 1.0);
        String html = "<!doctype html><body style='margin:0;background:#" + Integer.toHexString(COLOR) + "'>sandboxed</body>";
        WpeNative.load(view, WpeNative.LOAD_HTML, html, "https://sandbox-check.invalid/", null);
        long[] records = new long[5 * 256];
        boolean painted = false;
        for (long until = System.nanoTime() + 20_000_000_000L; System.nanoTime() < until && !painted; ) {
            Thread.sleep(100);
            int n = WpeNative.drainFrames(view, records);
            for (int i = 0; i < n; i++) painted |= records[i * 5 + 1] == COLOR;
        }
        System.out.println("SANDBOX loaded=" + finished.get() + " painted=" + painted);
        String nspid = webProcessPids();
        System.out.println("SANDBOX web process NSpid: " + nspid);
        if (!painted) fail("the page's colour never reached a frame");
        // bubblewrap gives the page's process a PID namespace of its own (--unshare-pid): two PIDs, the host's and its own.
        if (sandbox && nspid.trim().split("\\s+").length < 2) fail("the web process isn't in bubblewrap's sandbox: NSpid " + nspid);
        System.out.println("SANDBOX PASS");
        System.exit(0);
    }

    /**
     * The `NSpid` of this JVM's WPEWebProcess (from /proc, readable whatever namespace it is in): one PID per PID
     * namespace it is in. bubblewrap's own processes name the web process in their arguments too: the web process is
     * the one whose argv[0] it is.
     */
    private static String webProcessPids() throws Exception {
        for (ProcessHandle p : ProcessHandle.current().descendants().toList()) {
            String cmdline;
            try {
                cmdline = Files.readString(Path.of("/proc/" + p.pid() + "/cmdline"));
            } catch (Exception e) {
                continue;
            }
            if (!cmdline.split("\0", 2)[0].endsWith("/WPEWebProcess")) continue;
            for (String line : Files.readAllLines(Path.of("/proc/" + p.pid() + "/status"))) {
                if (line.startsWith("NSpid:")) return line.substring("NSpid:".length()).trim();
            }
        }
        return "";
    }

    private static void fail(String why) {
        System.out.println("SANDBOX FAIL " + why);
        System.exit(1);
    }
}

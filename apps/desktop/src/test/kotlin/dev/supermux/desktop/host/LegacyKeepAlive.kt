package dev.supermux.desktop.host

/** What 1.0.0's KeepAlive installed: it ran the APP (not the broker) under the broker service's names. */
object LegacyKeepAlive {
    const val APP_MAC = "/Applications/Supermux Desktop.app/Contents/MacOS/Supermux Desktop"

    val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>dev.supermux.host</string>
  <key>ProgramArguments</key>
  <array>
    <string>$APP_MAC</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>ProcessType</key>
  <string>Background</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>SUPERMUX_KEEP_ALIVE</key>
    <string>1</string>
    <key>SUPERMUX_HOST_ID</key>
    <string>abcdefghijklmnopqrstuvwxyz</string>
  </dict>
  <key>StandardOutPath</key>
  <string>/tmp/supermux-host.out.log</string>
  <key>StandardErrorPath</key>
  <string>/tmp/supermux-host.err.log</string>
</dict>
</plist>
"""

    val unit = """[Unit]
Description=supermux host (keep this computer available as a host)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/supermux/bin/supermux
Restart=on-failure
RestartSec=5
Environment=SUPERMUX_KEEP_ALIVE=1
Environment=SUPERMUX_HOST_ID=abcdefghijklmnopqrstuvwxyz

[Install]
WantedBy=default.target
"""

    val xdg = """[Desktop Entry]
Type=Application
Name=supermux host
Comment=Keep this computer available as a supermux host
Exec=/opt/supermux/bin/supermux
X-GNOME-Autostart-enabled=true
Terminal=false
"""

    /** The task as `schtasks /Query /XML` reports it. */
    val taskXml = """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>powershell.exe</Command>
      <Arguments>-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "&amp; { ${'$'}env:SUPERMUX_KEEP_ALIVE = '1'; &amp; 'C:\Program Files\supermux\supermux.exe' }"</Arguments>
      <WorkingDirectory>C:\Program Files\supermux</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""

    /** launchd started the app as the 1.0.0 job. */
    val macJobEnv = mapOf("XPC_SERVICE_NAME" to "dev.supermux.host", "SUPERMUX_KEEP_ALIVE" to "1")
    /** The app runs inside the 1.0.0 unit. */
    const val LINUX_JOB_CGROUP = "0::/user.slice/user-1000.slice/user@1000.service/app.slice/supermux-host.service\n"
}

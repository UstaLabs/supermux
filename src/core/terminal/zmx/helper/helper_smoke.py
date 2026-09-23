#!/usr/bin/env python3
"""On-the-wire smoke test for the zmx broker HELPER.

The unit tests on either side prove the framing; this proves the two ends plus
a real patched daemon agree about a real shell. It drives
`build/zmx/out/bin/mux-zmx-helper` exactly as src/core/terminal/zmx/helper.ts
does -- five-byte frames on stdin/stdout -- and asserts the things that are
only true end to end:

  * create runs an argv vector (no shell command string) and labels the
    session as it is created,
  * attach refuses a socket whose `mux.target` is not the key we asked for,
  * a focus claim takes the geometry with NO keystroke sent,
  * detaching the viewer, and KILLING the helper outright, both leave the
    target running,
  * `kill` is what destroys it,
  * the helper's own ZMX_SESSION never reaches the shell.

    python3 src/core/terminal/zmx/helper/helper_smoke.py [socket-dir]

Run scripts/build-zmx.sh first; this never installs anything system-wide.
"""
import hashlib, json, os, subprocess, sys, time, struct, select, signal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", ".."))
HELPER = f"{ROOT}/build/zmx/out/bin/mux-zmx-helper"
ZMX = f"{ROOT}/build/zmx/out/bin/zmx"
DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "supermux", "zmx-smoke")

def encode_name(scope, tid):
    return "muxterm_" + scope.encode().hex() + "_" + tid.encode().hex()

def socket_basename(scope, tid):
    d = hashlib.sha256(scope.encode() + b"\0" + tid.encode()).hexdigest()
    return "mx" + d[:20]

def frame(tag, payload: bytes) -> bytes:
    return bytes([tag]) + struct.pack("<I", len(payload)) + payload

def ctl(obj): return frame(1, json.dumps(obj).encode())

class Helper:
    def __init__(self):
        self.p = subprocess.Popen([HELPER, "--zmx", ZMX], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.buf = b""
        self.out = b""
    def send(self, data): self.p.stdin.write(data); self.p.stdin.flush()
    def pump(self, seconds=2.0):
        """Read frames for up to `seconds`; returns list of control messages."""
        msgs, deadline = [], time.time() + seconds
        while time.time() < deadline:
            r, _, _ = select.select([self.p.stdout], [], [], 0.2)
            if not r: continue
            chunk = os.read(self.p.stdout.fileno(), 65536)
            if not chunk: break
            self.buf += chunk
            while len(self.buf) >= 5:
                tag = self.buf[0]; ln = struct.unpack("<I", self.buf[1:5])[0]
                if len(self.buf) < 5 + ln: break
                payload = self.buf[5:5+ln]; self.buf = self.buf[5+ln:]
                if tag == 1: msgs.append(json.loads(payload))
                elif tag == 2: self.out += payload
        return msgs
    def wait_for(self, ev, seconds=5.0):
        deadline = time.time() + seconds
        seen = []
        while time.time() < deadline:
            got = self.pump(0.5)
            seen += got
            for m in got:
                if m.get("ev") == ev: return m, seen
        raise AssertionError(f"no {ev}; saw {seen}")

os.makedirs(DIR, mode=0o700, exist_ok=True)
scope, tid = "w:11111111-2222-3333-4444-555555555555", "main"
name, base = encode_name(scope, tid), socket_basename(scope, tid)
sock = f"{DIR}/{base}"
print("socket:", sock, len(sock), "bytes")

h = Helper()
hello, _ = h.wait_for("hello")
print("hello:", hello)
assert hello["abi"] == 1

# ZMX_SESSION is deliberately poisoned: the helper must drop it, or `zmx
# attach` would switch sessions instead of creating one and the shell would
# inherit a lie about which session it is in.
env = {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"], "TERM": "xterm-256color",
       "ZMX_SESSION": "should-be-stripped"}
h.send(ctl({"v": 1, "id": 1, "op": "create", "name": name, "socket": sock,
            "argv": ["/bin/bash", "--norc", "--noprofile"], "env": env,
            "cwd": "/tmp", "cols": 100, "rows": 30}))
ok, seen = h.wait_for("ok", 20)
print("create ok:", ok, "others:", [m for m in seen if m.get("ev") != "ok"])

h.send(ctl({"v": 1, "id": 2, "op": "attach", "name": name, "socket": sock, "cols": 100, "rows": 30}))
w, seen = h.wait_for("welcome", 5)
print("welcome:", w)
h.pump(1.0)

h.send(ctl({"v": 1, "id": 3, "op": "focus", "active": True, "cols": 100, "rows": 30}))
lease, _ = h.wait_for("lease", 5)
print("lease:", lease)

h.send(frame(3, b"echo HELPER_SMOKE_$((6*7))\r"))
deadline = time.time() + 5
while time.time() < deadline and b"HELPER_SMOKE_42" not in h.out:
    h.pump(0.5)
assert b"HELPER_SMOKE_42" in h.out, h.out[-500:]
print("input echoed, output bytes:", len(h.out))

# tput cols proves the focus geometry reached the pty without a keystroke claim
h.send(frame(3, b"tput cols\r"))
time.sleep(1.0); h.pump(1.0)
assert b"100" in h.out[-300:], h.out[-300:]
print("geometry applied (tput cols = 100)")

# The inherited ZMX_SESSION must not have reached the shell.
h.out = b""
h.send(frame(3, b"echo \"SESSION=[$ZMX_SESSION]\"\r"))
deadline = time.time() + 5
while time.time() < deadline and b"SESSION=[" not in h.out.split(b"echo")[-1]:
    h.pump(0.5)
assert b"should-be-stripped" not in h.out, h.out[-400:]
assert base.encode() in h.out, h.out[-400:]   # zmx sets its own, from the session name
print("inherited ZMX_SESSION stripped; shell sees the real session")

# A reply while we own the lease DOES reach the pty -- it is a keystroke as
# far as the shell is concerned, which is exactly why only the owner may send
# one. Sent last, because it lands on the shell's command line.
h.out = b""
h.send(frame(4, b"\x1b[?62;c"))
deadline = time.time() + 5
while time.time() < deadline and b"62" not in h.out:
    h.pump(0.5)
assert b"62" in h.out, h.out[-300:]
print("owner reply reached the pty")

h.send(ctl({"v": 1, "id": 4, "op": "detach"}))
code = h.p.wait(timeout=5)
print("helper exit code after detach:", code)
assert code == 0

# The TARGET must have survived the viewer.
out = subprocess.run([ZMX, "list"], env={**os.environ, "ZMX_DIR": DIR},
                     capture_output=True, text=True)
print("zmx list after detach:\n", out.stdout.strip(), out.stderr.strip())
assert base in out.stdout and name in out.stdout

# A second helper attaches to the same target: proves the label check and that
# nothing was lost when the first viewer went away.
h2 = Helper(); h2.wait_for("hello")
h2.send(ctl({"v": 1, "id": 1, "op": "list", "dir": DIR}))
lst, _ = h2.wait_for("ok", 5)
print("list:", lst)
assert any(e["name"] == name for e in lst["result"])

# Killing a helper OUTRIGHT must not take the shell with it: the viewer dies,
# the target does not.
h3 = Helper(); h3.wait_for("hello")
h3.send(ctl({"v": 1, "id": 1, "op": "attach", "name": name, "socket": sock, "cols": 80, "rows": 24}))
h3.wait_for("welcome", 5)
h3.p.send_signal(signal.SIGKILL)
h3.p.wait(timeout=5)
time.sleep(0.5)
out = subprocess.run([ZMX, "list"], env={**os.environ, "ZMX_DIR": DIR}, capture_output=True, text=True)
assert base in out.stdout, out.stdout
print("target survived SIGKILL of its helper")

# A mismatched name must be REFUSED, not attached.
h2.send(ctl({"v": 1, "id": 2, "op": "attach", "name": encode_name("w:other", "main"),
             "socket": sock, "cols": 80, "rows": 24}))
err, _ = h2.wait_for("error", 5)
print("mismatch refused:", err)
assert err["code"] == "protocol"

h2.send(ctl({"v": 1, "id": 3, "op": "kill", "socket": sock, "name": name}))
k, _ = h2.wait_for("ok", 5)
print("kill ok:", k)
time.sleep(1)
out = subprocess.run([ZMX, "list"], env={**os.environ, "ZMX_DIR": DIR}, capture_output=True, text=True)
assert base not in out.stdout, out.stdout
print("target gone after kill")
h2.p.stdin.close()
print("helper2 exit:", h2.p.wait(timeout=5))

# ---- the shell exits on its own --------------------------------------------
# The one path that may produce an `exit` event, and the one place the real
# waitpid status can be lost to a race with the pty closing.
scope2, tid2 = scope, "exiting"
name2, base2 = encode_name(scope2, tid2), socket_basename(scope2, tid2)
sock2 = f"{DIR}/{base2}"
h4 = Helper(); h4.wait_for("hello")
h4.send(ctl({"v": 1, "id": 1, "op": "create", "name": name2, "socket": sock2,
             "argv": ["/bin/bash", "--norc", "--noprofile"], "env": env,
             "cwd": "/tmp", "cols": 80, "rows": 24}))
h4.wait_for("ok", 20)
h4.send(ctl({"v": 1, "id": 2, "op": "attach", "name": name2, "socket": sock2, "cols": 80, "rows": 24}))
h4.wait_for("welcome", 5)
h4.send(frame(3, b"exit 7\r"))
ev, _ = h4.wait_for("exit", 10)
print("exit event:", ev)
assert ev["known"] is True and ev["code"] == 7 and ev["signal"] is None, ev
h4.p.stdin.close(); h4.p.wait(timeout=5)
print("PASS helper-smoke")
err_text = h.p.stderr.read().decode()
if err_text: print("helper1 stderr:\n", err_text)

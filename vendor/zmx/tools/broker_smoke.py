#!/usr/bin/env python3
"""On-the-wire smoke test for the supermux broker attachment.

Speaks the patched protocol against a REAL running zmx daemon socket, so the
framing, the handshake, the replay boundary, the focus lease and input all get
exercised end to end rather than through the daemon's handlers in-process.
"""
import socket, struct, sys, time

HDR = 8
T = dict(Output=1, BrokerHello=22, BrokerWelcome=23, BrokerFocus=24,
         BrokerLease=25, BrokerResize=26, BrokerInput=27, BrokerReply=28,
         BrokerReplayStart=29, BrokerReplayEnd=30, BrokerExit=31,
         BrokerFailure=32, BrokerDetach=33)
NAME = {v: k for k, v in T.items()}

def frame(tag, payload=b""):
    return struct.pack("<Q", tag | (len(payload) << 8)) + payload

def read_frames(sock, seconds):
    sock.settimeout(0.3)
    buf, out, deadline = b"", [], time.time() + seconds
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
        except socket.timeout:
            continue
        if not chunk:
            break
        buf += chunk
        while len(buf) >= HDR:
            v = struct.unpack("<Q", buf[:HDR])[0]
            tag, ln = v & 0xFF, (v >> 8) & 0xFFFFFFFF
            if len(buf) < HDR + ln:
                break
            out.append((tag, buf[HDR:HDR + ln]))
            buf = buf[HDR + ln:]
    return out

def main(path, version=1):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(path)
    hello = b"ZMXB" + struct.pack("<HHI", version, 12, 0) + b"muxterm_77_6d61696e"
    s.sendall(frame(T["BrokerHello"], hello))
    frames = read_frames(s, 2.0)
    tags = [NAME.get(t, t) for t, _ in frames]
    print("after hello:", tags[:6], "..." if len(tags) > 6 else "")

    if version != 1:
        assert "BrokerFailure" in tags, tags
        code, _ = struct.unpack("<HH", dict(frames)[T["BrokerFailure"]][:4])
        print("  refused, code =", code)
        assert code == 1, code            # unsupported_version
        assert "BrokerWelcome" not in tags, tags
        print("PASS version-negotiation")
        return

    assert tags[0] == "BrokerWelcome", tags
    ver, slen, gen, pend, snap = struct.unpack("<xxxxHHQII", frames[0][1])
    print(f"  welcome version={ver} lease_gen={gen} pending_max={pend} snapshot_max={snap}")
    assert (ver, slen) == (1, 24)
    assert tags.index("BrokerReplayStart") < tags.index("BrokerReplayEnd"), tags
    e0, b0 = struct.unpack("<QQ", dict(frames)[T["BrokerReplayStart"]])
    e1, b1 = struct.unpack("<QQ", dict(frames)[T["BrokerReplayEnd"]])
    assert e0 == e1 and e0 > 0, (e0, e1)
    assert b0 == b1, (b0, b1)
    print(f"  replay epoch={e0} bytes={b0}")

    # Focus: no keystroke has been sent, and this must still take the size.
    s.sendall(frame(T["BrokerFocus"], struct.pack("<HHHHBBBB", 30, 100, 0, 0, 1, 0, 0, 0)))
    frames = read_frames(s, 1.5)
    lease = dict(frames).get(T["BrokerLease"])
    assert lease is not None, [NAME.get(t, t) for t, _ in frames]
    lease_gen, rows, cols = struct.unpack("<QHH", lease[:12])
    print(f"  lease gen={lease_gen} rows={rows} cols={cols}")
    assert lease_gen == gen + 1, (lease_gen, gen)
    assert (rows, cols) == (30, 100)

    # Input reaches the shell.
    s.sendall(frame(T["BrokerInput"], b"echo BROKER_WIRE_OK\r"))
    frames = read_frames(s, 3.0)
    data = b"".join(p for t, p in frames if t == T["Output"])
    assert b"BROKER_WIRE_OK" in data, data[-400:]
    print("  input echoed back through Output")

    # A resize under a stale generation must not take effect; under the live
    # one it must.
    s.sendall(frame(T["BrokerResize"], struct.pack("<QHHHH", lease_gen - 1, 5, 5, 0, 0)))
    s.sendall(frame(T["BrokerInput"], b"tput cols\r"))
    time.sleep(0.8)
    frames = read_frames(s, 2.0)
    data = b"".join(p for t, p in frames if t == T["Output"])
    assert b"100" in data, data[-400:]
    print("  stale-generation resize ignored (tput cols still 100)")
    print("PASS broker-wire")

if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 1)

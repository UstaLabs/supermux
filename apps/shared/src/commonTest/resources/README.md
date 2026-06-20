# RFB/VNC test fixtures

These binary fixtures back the `VncClient`/`RfbCodec`/`ZrleDecoder` tests.

## `rfb-zrle-session.bin` — real server→client RFB byte stream

A verbatim capture of the **server→client** bytes from the broker's VNC
byte-tunnel (`GET /ws/display?id=<id>`), from the ProtocolVersion greeting
through the first `FramebufferUpdate` that carries a **ZRLE** rectangle.

Layout (RFB 3.8, RFC 6143), as verified by the reference decoder:

```
"RFB 003.008\n"                       (12 bytes)
01                                    security-type count = 1
01                                    types = [None]
00 00 00 00                           SecurityResult = OK
05 00 03 20  ...16B PIXEL_FORMAT...   ServerInit: 1280x800, server PF
00 00 00 0C "ustalabs:100"            name
00 00 00 01                           FramebufferUpdate: type 0, pad, nRects=1
00 00 00 00 05 00 03 20 00 00 00 10   rect (0,0,1280,800) enc=16 (ZRLE)
00 00 12 92  <4754 ZRLE bytes>        u32 len + zlib stream
```

The single rect inflates to 10433 bytes covering a 20×13 tile grid (260 tiles).
Observed sub-encodings: solid(1), plain-RLE(128), palette-RLE(130..255),
exercising most of the ZRLE paths against real x11vnc output (glxgears + xclock
+ xeyes on `DISPLAY=:100`).

### Golden values (from the reference decoder `capture-rfb.ts` cross-check)

Decoded full-frame BGRA buffer (1280*800*4 bytes) SHA-256 prefix:
`bd52e7d8fd6de8fc`. Sample pixels (BGRA):

| (x,y)       | BGRA               |
|-------------|--------------------|
| (0,0)       | [0,0,0,255]        |
| (640,400)   | [95,58,31,255]     |
| (200,150)   | [48,193,0,255]     |
| (75,75)     | [242,48,48,255]    |
| (1279,799)  | [95,58,31,255]     |

## `rfb-client-handshake.bin` — golden client→server bytes

The exact bytes our capture client sent: the `"RFB 003.008\n"` reply, the
`None` security pick (`01`), `ClientInit` (`01`), `SetPixelFormat` (pinned 32bpp
BGRA), `SetEncodings([16,1,0,-223])`, and a full `FramebufferUpdateRequest`.
Used as the golden output for the encoder tests in `RfbCodecTest`.

## How they were captured

Broker running on `http://localhost:9898`, live `linux-xvfb` VNC display
`d-1504c1bf` (`DISPLAY=:100`). Device token minted via
`bun scripts/pair.ts vnc-fixture` (verify with `GET /me` → `{"paired":true}`).

```
bun apps/shared/src/commonTest/resources/capture-rfb.ts <displayId> <token> localhost:9898
```

`capture-rfb.ts` drives the RFB 3.8 handshake (the same byte encoders the Kotlin
`RfbCodec` produces), records every server→client byte, and stops after the
first ZRLE-bearing FramebufferUpdate.

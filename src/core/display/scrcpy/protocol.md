# scrcpy wire protocol — pinned reference

**Installed version:** scrcpy **3.3.4** (server jar: `/usr/share/scrcpy/scrcpy-server`, 90980 bytes).
**Confirmed against:** live `emulator-5554` (sdk_gphone64_x86_64, Android 15) on 2026-05-29.
Fixture of a real video-socket handshake: `tests/fixtures/scrcpy-video-head.bin` (120 bytes).

If scrcpy is upgraded, re-capture and update this file + the parsers.

## Launch

```
adb push /usr/share/scrcpy/scrcpy-server /data/local/tmp/scrcpy-server.jar
adb forward tcp:<localPort> localabstract:scrcpy_<scid>      # scid = 8 hex digits, random
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / \
  com.genymobile.scrcpy.Server 3.3.4 \
  scid=<scid> log_level=info tunnel_forward=true \
  audio=false control=<true|false> video=true \
  video_codec=h264 max_size=<px> video_bit_rate=<bps> send_frame_meta=true
```
- `tunnel_forward=true`: the **server listens** on `localabstract:scrcpy_<scid>`; the client connects through the `adb forward`. Connect the **video socket first**, then (if `control=true`) the **control socket** — both to the same forwarded port (the server accepts them in order).
- Defaults that are ON: `send_dummy_byte=true`, `send_device_meta=true`, `send_codec_meta=true`, `send_frame_meta=true`.

## Video socket byte layout (confirmed from the fixture)

1. **Dummy byte** (1): `0x00` (only on the first/video socket; presence = `send_dummy_byte`).
2. **Device name** (64): UTF-8, null-padded. (e.g. `"sdk_gphone64_x86_64"`.) Presence = `send_device_meta`.
3. **Codec metadata** (12): `codecId u32` (big-endian ASCII, `0x68323634` = `"h264"`) + `width u32` + `height u32`. Presence = `send_codec_meta`.
4. **Frames**, repeating (presence of header = `send_frame_meta`):
   - `pts_flags` (u64, big-endian): top bits are flags —
     - bit 63 (`0x8000000000000000`) = **CONFIG** packet (carries SPS/PPS; send to the decoder as description / prepend to first keyframe).
     - bit 62 (`0x4000000000000000`) = **KEY_FRAME**.
     - remaining bits = PTS (microseconds).
   - `size` (u32, big-endian) = payload length.
   - `payload` = `size` bytes of **Annex-B H.264** (`00 00 00 01` start codes). The first packet is the CONFIG packet (SPS `…00000001 67…` + PPS `…00000001 68…`).

All multi-byte integers are **big-endian**.

## Control socket (client → server) — message layout

Each message is a 1-byte type then a fixed body (big-endian). Types (scrcpy 3.x `ControlMessage`):
- `0` INJECT_KEYCODE: `action u8 | keycode u32 | repeat u32 | metaState u32`
- `1` INJECT_TEXT: `len u32 | utf8 bytes`
- `2` INJECT_TOUCH_EVENT: `action u8 | pointerId u64 | x u32 | y u32 | screenWidth u16 | screenHeight u16 | pressure u16 (16.16 fixed → 0xFFFF=1.0) | actionButton u32 | buttons u32`
- `3` INJECT_SCROLL_EVENT: `x u32 | y u32 | screenWidth u16 | screenHeight u16 | hscroll i16 | vscroll i16 | buttons u32`

`action` for touch: `0`=DOWN, `1`=UP, `2`=MOVE (AMOTION_EVENT_ACTION_*). For key: `0`=DOWN, `1`=UP. `pointerId = 0xFFFFFFFFFFFFFFFF` for a generic finger. x/y are device-pixel coords; screenWidth/Height are the current frame dims (from codec meta) so the server scales.

> The control layout above is from the scrcpy 3.x source convention; verify field sizes against `com.genymobile.scrcpy.ControlMessageReader` for 3.3.4 if any inject misbehaves (the video layout is fixture-confirmed).

## Teardown

Kill the `adb shell app_process` process, `adb forward --remove tcp:<localPort>`, close both sockets. The server has `cleanup=true` by default (restores device state).

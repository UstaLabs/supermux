"""How far each live page was from its card in a recording of the screen: what the display showed, frame by frame.

The macOS skew runs measure window copies (CardSkew in the sample), and a copy composites what the window server has
been sent, which between a commit and the next refresh can pair a page view's new place with the Compose frame before.
A recording of the screen has only what the display showed. Record on the Mac from an SSH session (it may record the
screen; java may not), while the run draws its lines (PreviewMarkers):

    SKEW=1 bash apps/webview-sample/device-checks/mac-engine-checks.sh &   # or the trackpad check
    sleep 40; screencapture -v -V 28 -x /tmp/skew.mov                      # about 28 s records whole

then, where ffmpeg and numpy are (not the Mac):

    CROP=96:60:2200:1612 python3 screen-skew.py skew.mov 1100 806 0 run=0:28

CROP is the window's content in the recording's pixels (x:y:w:h; the window at 48,30 points on a 2x screen, its content
1100 x 806 points), decoded at a pixel a point. Each segment (label=from:to, seconds into the recording) prints the moving
cards' skew (points: the page's lines against its caption's) and how long the card took to get where its page was
(frames at 60 Hz), p50 / p95 / max, as CardSkew reads copies.
"""
import os
import subprocess
import sys

import numpy as np

MAGENTA_B, YELLOW_B = 40, 150  # PreviewMarkers: the page's lines, points above its bottom
SEARCH = 170
MAX_ROWS = 7
MIN_RUN = 300
OFF = 2.0
FRAME = 1 / 60


def masks(frame):
    """The lines' inks, as CardSkew reads them: each channel high (>= 170) or low (<= 90)."""
    r, g, b = (frame[:, :, k].astype(np.int16) for k in range(3))
    rh, rl, gh, gl, bh, bl = r >= 170, r <= 90, g >= 170, g <= 90, b >= 170, b <= 90
    return {"cyan": rl & gh & bh, "blue": rl & gl & bh, "magenta": rh & gl & bh, "yellow": rh & gh & bl}


def lines_of(mask):
    """Centres of the rows with a line of the ink, a few rows thick at most."""
    rows = np.nonzero(mask.sum(axis=1) >= MIN_RUN)[0]
    out = []
    if rows.size == 0:
        return out
    start = prev = rows[0]
    for y in rows[1:]:
        if y != prev + 1:
            out.append((start, prev))
            start = y
        prev = y
    out.append((start, prev))
    return [(a + b) / 2.0 for a, b in out if b - a + 1 <= MAX_ROWS]


def marks(frame):
    """(caption centre, skew) of each live card: cyan captions, the page's lines above them, under the card above's."""
    lines = {k: lines_of(v) for k, v in masks(frame).items()}
    captions = sorted([(c, True) for c in lines["cyan"]] + [(c, False) for c in lines["blue"]])
    result = []
    for i, (cy, live) in enumerate(captions):
        if not live:
            continue
        above = captions[i - 1][0] if i > 0 else -1e9
        skews = []
        for ink, from_bottom in (("magenta", MAGENTA_B), ("yellow", YELLOW_B)):
            expected = cy - from_bottom
            near = [c for c in lines[ink] if above < c < cy and abs(c - expected) <= SEARCH]
            if near:
                skews.append(min(near, key=lambda c: abs(c - expected)) - expected)
        if skews:
            result.append((cy, sum(skews) / len(skews)))
    return result


def pct(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    return s[max(0, min(len(s) - 1, int(np.ceil(p * len(s))) - 1))]


def summarize(samples, label):
    """samples: (time, caption, skew) per card per frame; cards are followed by their nearest caption."""
    tracks = []
    for t, y, skew in samples:
        best = None
        for track in tracks:
            lt, ly = track[-1][0], track[-1][1]
            if t - lt <= 0.2 and abs(ly - y) <= 150 and lt != t and (best is None or abs(ly - y) < abs(best[-1][1] - y)):
                best = track
        if best is None:
            tracks.append([(t, y, skew)])
        else:
            best.append((t, y, skew))
    px, frames, off, ahead = [], [], 0, 0
    for track in tracks:
        for i, (t, y, skew) in enumerate(track):
            near = [s for s in track if abs(s[0] - t) <= 0.04]
            if len(near) < 2 or near[-1][0] - near[0][0] < 0.03:
                continue
            v = (near[-1][1] - near[0][1]) / (near[-1][0] - near[0][0]) * FRAME  # points a frame
            if abs(v) < 2:
                continue
            a = abs(skew)
            px.append(a)
            reach = None
            if a > OFF:
                off += 1
                if skew * v > 0:
                    ahead += 1
                target, step = y + skew, (1 if skew * v > 0 else -1)
                j = i
                while 0 <= j + step < len(track) and abs(track[j + step][0] - t) <= 0.4:
                    p, q = track[j], track[j + step]
                    if (p[1] - target) * (q[1] - target) <= 0 and p[1] != q[1]:
                        reach = p[0] + (target - p[1]) / (q[1] - p[1]) * (q[0] - p[0]) - t
                        break
                    j += step
            frames.append(abs(reach) / FRAME if reach is not None else a / abs(v))
    print(f"{label}: {len(px)} moving cards: skew p50 {pct(px, .5):.1f} / p95 {pct(px, .95):.1f} / max {max(px or [0]):.1f} pt, "
          f"p50 {pct(frames, .5):.2f} / p95 {pct(frames, .95):.2f} / max {max(frames or [0]):.2f} frames; "
          f"{off} more than {OFF:.0f} pt off, {ahead} of them ahead")


def main():
    video, w, h, start = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
    segments = []
    for arg in sys.argv[5:]:
        label, span = arg.split("=")
        a, b = span.split(":")
        segments.append((label, float(a), float(b)))
    # The frames' times, from the packets (no decoding), in display order.
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time",
                            "-of", "csv=p=0", video], capture_output=True, text=True).stdout.split()
    times = sorted(float(x.strip(",")) for x in probe if x.strip(",").strip())
    crop = os.environ.get("CROP")
    vf = f"scale={w}:{h}:flags=area"
    if crop:
        x, y, cw, ch = crop.split(":")
        vf = f"crop={cw}:{ch}:{x}:{y}," + vf
    proc = subprocess.Popen(["ffmpeg", "-v", "error", "-threads", "4", "-i", video, "-vf", vf, "-f", "rawvideo",
                             "-pix_fmt", "rgb24", "-vsync", "passthrough", "-"], stdout=subprocess.PIPE)
    size = w * h * 3
    per_segment = {label: [] for label, _, _ in segments}
    n = 0
    while True:
        buf = proc.stdout.read(size)
        if len(buf) < size:
            break
        t = start + (times[n] if n < len(times) else n * FRAME)
        n += 1
        segment = next((label for label, a, b in segments if a <= t < b), None)
        if segment is None:
            continue
        frame = np.frombuffer(buf, dtype=np.uint8).reshape(h, w, 3)
        for cy, skew in marks(frame):
            per_segment[segment].append((t, cy, skew))
    proc.wait()
    print(f"{n} frames")
    for label, _, _ in segments:
        summarize(per_segment[label], label)


if __name__ == "__main__":
    main()

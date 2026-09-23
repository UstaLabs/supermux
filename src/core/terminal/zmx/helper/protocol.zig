//! Helper protocol v1: the framing between the broker (Bun) and this helper.
//!
//! This is the Zig half of `src/core/terminal/zmx/protocol.ts`; the two are
//! the same five-byte envelope described from either end:
//!
//!     tag u8 | payload length u32 LITTLE-ENDIAN | payload
//!
//! Deliberately NOT zmx's own `ipc.Header`, which is a packed struct with
//! three bytes of tail padding and native endianness. Those are fine between
//! two Zig processes on one machine and wrong for a wire whose other end is
//! hand-decoding bytes in TypeScript: every platform-dependent part of a
//! format is a bug the other end gets to discover in production.
//!
//! Payloads are capped at 64 KiB. Output is chunked to fit; a control message
//! that does not fit is an error, never split.
//!
//! stdout carries frames and NOTHING else. Every diagnostic goes to stderr.

const std = @import("std");

/// Bumped when the framing or the control vocabulary changes incompatibly.
/// Reported by `--version-json` and in the `hello` event, and checked by the
/// broker before it trusts a single byte from this process.
pub const ABI_VERSION: u32 = 1;

pub const HEADER_LEN: usize = 5;
pub const MAX_PAYLOAD: usize = 64 * 1024;

/// Everything the reader can ever hold: one header plus one maximum payload.
/// A constant, not a function of the length field on the wire.
pub const PENDING_MAX: usize = HEADER_LEN + MAX_PAYLOAD;

pub const Tag = enum(u8) {
    control = 1,
    output = 2,
    input = 3,
    reply = 4,
    // Non-exhaustive so an unknown tag off the wire is a value we can reject,
    // not undefined behaviour.
    _,
};

pub const Frame = struct {
    tag: Tag,
    /// Valid until the next `push`. Copy anything kept longer.
    payload: []const u8,
};

pub const DecodeError = error{
    /// A tag byte that is not one of ours.
    BadTag,
    /// A length over MAX_PAYLOAD. Rejected from the header alone, so a peer
    /// cannot make us buffer by claiming a size it will never send.
    TooLong,
};

/// Stream decoder over a fixed buffer. The broker's stdout arrives in
/// pipe-sized lumps with no relation to frame boundaries, so every push may
/// complete zero, one or many frames and leave a fragment behind.
pub const FrameReader = struct {
    buf: [PENDING_MAX]u8 = undefined,
    len: usize = 0,
    cursor: usize = 0,
    failed: bool = false,

    /// Bytes that can be read in right now. Always > 0 unless a single frame
    /// is mid-flight and already at the maximum, which cannot be exceeded.
    pub fn writable(self: *FrameReader) []u8 {
        self.compact();
        return self.buf[self.len..];
    }

    /// Commit `n` bytes previously read into `writable()`.
    pub fn commit(self: *FrameReader, n: usize) void {
        self.len += n;
    }

    /// The next complete frame, or null when more bytes are needed.
    pub fn next(self: *FrameReader) DecodeError!?Frame {
        const available = self.buf[self.cursor..self.len];
        if (available.len < HEADER_LEN) return null;
        const raw_tag = available[0];
        const tag: Tag = @enumFromInt(raw_tag);
        switch (tag) {
            .control, .output, .input, .reply => {},
            _ => {
                self.failed = true;
                return error.BadTag;
            },
        }
        const payload_len: usize = std.mem.readInt(u32, available[1..5], .little);
        if (payload_len > MAX_PAYLOAD) {
            self.failed = true;
            return error.TooLong;
        }
        const total = HEADER_LEN + payload_len;
        if (available.len < total) return null;
        self.cursor += total;
        return .{ .tag = tag, .payload = available[HEADER_LEN..total] };
    }

    /// True when the stream is between frames — the only place EOF is clean.
    pub fn atBoundary(self: *const FrameReader) bool {
        return self.cursor == self.len;
    }

    fn compact(self: *FrameReader) void {
        if (self.cursor == 0) return;
        const remaining = self.len - self.cursor;
        if (remaining > 0) {
            std.mem.copyForwards(u8, self.buf[0..remaining], self.buf[self.cursor..self.len]);
        }
        self.len = remaining;
        self.cursor = 0;
    }
};

pub const WriteError = error{ WouldBlock, WriteFailed };

/// Blocking best-effort write of the whole slice. For diagnostics only.
pub fn writeAllBlocking(fd: i32, bytes: []const u8) void {
    var at: usize = 0;
    while (at < bytes.len) {
        const n = writeSome(fd, bytes[at..]) catch return;
        if (n == 0) return;
        at += n;
    }
}

/// Outbound frames, queued and drained as the pipe accepts them.
///
/// Queued rather than written straight through because a blocked stdout must
/// not stop us reading the daemon socket: we stop reading it DELIBERATELY at
/// the high-water mark (see main.zig), which pushes backpressure onto the
/// daemon, whose own 1 MiB cap then detaches this viewer with
/// `resync_required` — a defined outcome instead of a stall.
pub const OutBuf = struct {
    gpa: std.mem.Allocator,
    list: std.ArrayList(u8) = .empty,
    sent: usize = 0,

    pub fn deinit(self: *OutBuf) void {
        self.list.deinit(self.gpa);
    }

    pub fn pending(self: *const OutBuf) usize {
        return self.list.items.len - self.sent;
    }

    pub fn frame(self: *OutBuf, tag: Tag, payload: []const u8) !void {
        if (payload.len > MAX_PAYLOAD) return error.PayloadTooLong;
        var header: [HEADER_LEN]u8 = undefined;
        header[0] = @intFromEnum(tag);
        std.mem.writeInt(u32, header[1..5], @intCast(payload.len), .little);
        try self.list.ensureTotalCapacity(self.gpa, self.list.items.len + HEADER_LEN + payload.len);
        self.list.appendSliceAssumeCapacity(&header);
        self.list.appendSliceAssumeCapacity(payload);
    }

    /// Frame `bytes` as output, splitting at MAX_PAYLOAD. Order is preserved;
    /// a zero-length write produces no frame.
    pub fn output(self: *OutBuf, bytes: []const u8) !void {
        var at: usize = 0;
        while (at < bytes.len) {
            const end = @min(at + MAX_PAYLOAD, bytes.len);
            try self.frame(.output, bytes[at..end]);
            at = end;
        }
    }

    /// Write what the pipe will take. Returns false if the far end is gone.
    pub fn flush(self: *OutBuf, fd: i32) bool {
        while (self.sent < self.list.items.len) {
            const n = writeSome(fd, self.list.items[self.sent..]) catch |err| switch (err) {
                error.WouldBlock => break,
                else => return false,
            };
            if (n == 0) break;
            self.sent += n;
        }
        if (self.sent == self.list.items.len) {
            self.list.clearRetainingCapacity();
            self.sent = 0;
        } else if (self.sent > 64 * 1024) {
            // Reclaim the drained prefix so a long-lived helper's buffer does
            // not grow without bound while it is never fully empty.
            const remaining = self.list.items.len - self.sent;
            std.mem.copyForwards(u8, self.list.items[0..remaining], self.list.items[self.sent..]);
            self.list.items.len = remaining;
            self.sent = 0;
        }
        return true;
    }
};

extern "c" fn write(fd: i32, buf: [*]const u8, count: usize) isize;

/// One `write(2)`, with EINTR retried and EAGAIN surfaced. Zig 0.16's
/// std.posix no longer wraps write, so this is the whole of our path to a fd.
pub fn writeSome(fd: i32, bytes: []const u8) WriteError!usize {
    while (true) {
        const rc = write(fd, bytes.ptr, bytes.len);
        if (rc >= 0) return @intCast(rc);
        switch (std.posix.errno(rc)) {
            .INTR => continue,
            .AGAIN => return error.WouldBlock,
            else => return error.WriteFailed,
        }
    }
}

// ---------------------------------------------------------------------------
//  JSON
// ---------------------------------------------------------------------------

/// Append a JSON string literal.
///
/// Escapes conservatively: quote, backslash, and EVERY byte outside printable
/// ASCII as `\u00XX`. Strings crossing this wire include paths and error text
/// that are not guaranteed to be valid UTF-8, and the broker's decoder is
/// strict — one invalid byte would fail the whole stream rather than the one
/// diagnostic that carried it.
pub fn appendJsonString(gpa: std.mem.Allocator, list: *std.ArrayList(u8), s: []const u8) !void {
    try list.append(gpa, '"');
    for (s) |c| {
        switch (c) {
            '"' => try list.appendSlice(gpa, "\\\""),
            '\\' => try list.appendSlice(gpa, "\\\\"),
            0x20...0x21, 0x23...0x5b, 0x5d...0x7e => try list.append(gpa, c),
            else => {
                var buf: [6]u8 = undefined;
                const hex = try std.fmt.bufPrint(&buf, "\\u{x:0>4}", .{c});
                try list.appendSlice(gpa, hex);
            },
        }
    }
    try list.append(gpa, '"');
}

/// Append `"key":` — the caller writes the value.
pub fn appendJsonKey(gpa: std.mem.Allocator, list: *std.ArrayList(u8), key: []const u8) !void {
    if (list.items.len > 0 and list.items[list.items.len - 1] != '{') try list.append(gpa, ',');
    try appendJsonString(gpa, list, key);
    try list.append(gpa, ':');
}

pub fn appendJsonNum(gpa: std.mem.Allocator, list: *std.ArrayList(u8), value: anytype) !void {
    var buf: [32]u8 = undefined;
    const text = try std.fmt.bufPrint(&buf, "{d}", .{value});
    try list.appendSlice(gpa, text);
}

// ---------------------------------------------------------------------------
//  Tests
// ---------------------------------------------------------------------------

test "envelope is five bytes, little-endian, and round-trips" {
    var out: OutBuf = .{ .gpa = std.testing.allocator };
    defer out.deinit();
    try out.frame(.output, "abc");
    try std.testing.expectEqual(@as(usize, 8), out.list.items.len);
    try std.testing.expectEqualSlices(u8, &.{ 2, 3, 0, 0, 0, 'a', 'b', 'c' }, out.list.items);

    var reader: FrameReader = .{};
    const room = reader.writable();
    @memcpy(room[0..out.list.items.len], out.list.items);
    reader.commit(out.list.items.len);
    const frame = (try reader.next()).?;
    try std.testing.expectEqual(Tag.output, frame.tag);
    try std.testing.expectEqualStrings("abc", frame.payload);
    try std.testing.expect(reader.atBoundary());
}

test "a frame split at every byte decodes identically" {
    var out: OutBuf = .{ .gpa = std.testing.allocator };
    defer out.deinit();
    try out.frame(.control, "{\"v\":1,\"op\":\"detach\"}");
    try out.frame(.input, "\x1b[A");
    const stream = out.list.items;

    var split: usize = 0;
    while (split <= stream.len) : (split += 1) {
        var reader: FrameReader = .{};
        var seen: usize = 0;
        var tags: [2]Tag = undefined;
        for ([_][]const u8{ stream[0..split], stream[split..] }) |chunk| {
            const room = reader.writable();
            @memcpy(room[0..chunk.len], chunk);
            reader.commit(chunk.len);
            while (try reader.next()) |frame| {
                tags[seen] = frame.tag;
                seen += 1;
            }
        }
        try std.testing.expectEqual(@as(usize, 2), seen);
        try std.testing.expectEqual(Tag.control, tags[0]);
        try std.testing.expectEqual(Tag.input, tags[1]);
        try std.testing.expect(reader.atBoundary());
    }
}

test "a zero-length payload is a frame" {
    var out: OutBuf = .{ .gpa = std.testing.allocator };
    defer out.deinit();
    try out.frame(.input, "");
    var reader: FrameReader = .{};
    const room = reader.writable();
    @memcpy(room[0..out.list.items.len], out.list.items);
    reader.commit(out.list.items.len);
    const frame = (try reader.next()).?;
    try std.testing.expectEqual(Tag.input, frame.tag);
    try std.testing.expectEqual(@as(usize, 0), frame.payload.len);
}

test "an unknown tag and an oversized length are rejected" {
    var reader: FrameReader = .{};
    var room = reader.writable();
    @memcpy(room[0..5], &[_]u8{ 9, 0, 0, 0, 0 });
    reader.commit(5);
    try std.testing.expectError(error.BadTag, reader.next());

    var reader2: FrameReader = .{};
    room = reader2.writable();
    // 0xffffffff: the length a careless decoder allocates from.
    @memcpy(room[0..5], &[_]u8{ 2, 0xff, 0xff, 0xff, 0xff });
    reader2.commit(5);
    try std.testing.expectError(error.TooLong, reader2.next());
}

test "a partial frame is retained and never reported as complete" {
    var reader: FrameReader = .{};
    const room = reader.writable();
    @memcpy(room[0..6], &[_]u8{ 2, 4, 0, 0, 0, 'x' });
    reader.commit(6);
    try std.testing.expectEqual(@as(?Frame, null), try reader.next());
    try std.testing.expect(!reader.atBoundary());
    const more = reader.writable();
    @memcpy(more[0..3], "yz!");
    reader.commit(3);
    const frame = (try reader.next()).?;
    try std.testing.expectEqualStrings("xyz!", frame.payload);
    try std.testing.expect(reader.atBoundary());
}

test "output is chunked at the frame maximum, in order" {
    var out: OutBuf = .{ .gpa = std.testing.allocator };
    defer out.deinit();
    const big = try std.testing.allocator.alloc(u8, MAX_PAYLOAD * 2 + 7);
    defer std.testing.allocator.free(big);
    for (big, 0..) |*b, i| b.* = @truncate(i);
    try out.output(big);

    var reader: FrameReader = .{};
    var seen: usize = 0;
    var at: usize = 0;
    while (at < out.list.items.len) {
        const room = reader.writable();
        const take = @min(room.len, out.list.items.len - at);
        @memcpy(room[0..take], out.list.items[at..][0..take]);
        reader.commit(take);
        at += take;
        while (try reader.next()) |frame| {
            try std.testing.expectEqualSlices(u8, big[seen..][0..frame.payload.len], frame.payload);
            seen += frame.payload.len;
        }
    }
    try std.testing.expectEqual(big.len, seen);
}

test "JSON strings escape quotes, control bytes and non-ASCII" {
    const gpa = std.testing.allocator;
    var list: std.ArrayList(u8) = .empty;
    defer list.deinit(gpa);
    try appendJsonString(gpa, &list, "a\"b\\c\nd\xffe");
    try std.testing.expectEqualStrings("\"a\\\"b\\\\c\\u000ad\\u00ffe\"", list.items);
}

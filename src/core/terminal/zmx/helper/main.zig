//! mux-zmx-helper — the process that speaks the patched zmx broker protocol.
//!
//! WHY IT EXISTS. zmx's IPC is native-endian extern structs behind an 8-byte
//! packed header on an AF_UNIX socket. Decoding that in TypeScript would mean
//! a hand-maintained second copy of every struct layout, re-derived padding
//! rules, and a silent misread the first time upstream adds a field. So this
//! helper is compiled against the PATCHED zmx source's own `ipc.zig` — the
//! definitions are the daemon's, by construction — and talks to the broker
//! over the small, explicitly-sized envelope in `protocol.zig`.
//!
//! ONE HELPER PROCESS IS ONE VIEWER.
//!
//!   * `attach` binds this process to one target for its lifetime.
//!   * Killing the helper detaches that viewer. The target SURVIVES: the
//!     shell belongs to a daemon nobody here owns.
//!   * Destroying a target is `kill`, a separate explicit command.
//!   * Losing the socket is reported as `lost`, NEVER as an exit. "Your
//!     program ended" closes a tab; "I cannot see your program" retries. Only
//!     the daemon's `BrokerExit` — which carries a real waitpid status — is
//!     allowed to produce an `exit` event.
//!
//! IDENTITY. The socket basename is a 20-hex truncation of SHA-256 over the
//! supermux key, so it cannot prove which terminal is behind it. The full key
//! rides in the daemon's `mux.target` label, and EVERY attach reads that label
//! and compares it BEFORE sending anything else — the check `names.ts` calls
//! `assertTargetMatches`. A mismatch is refused, never attached: two
//! workspaces sharing one shell is the failure the whole naming scheme exists
//! to prevent.

const std = @import("std");
const ipc = @import("zmx_ipc");
const label = @import("zmx_label");
const proto = @import("protocol.zig");
const build_options = @import("build_options");

/// Must match `TARGET_LABEL_KEY` in ../names.ts and `BROKER_TARGET_LABEL` in
/// vendor/zmx/patches/0001-supermux-session-contract.patch.
const TARGET_LABEL = "mux.target";

/// Stop draining the daemon past this much queued stdout. Backpressure, on
/// purpose: the daemon's own 1 MiB cap then detaches us with
/// `resync_required`, which is a defined outcome the broker can re-attach
/// from — unlike growing until something dies.
const STDOUT_HIGH_WATER: usize = 4 * 1024 * 1024;

const STDIN: i32 = 0;
const STDOUT: i32 = 1;
const STDERR: i32 = 2;

// libc directly: Zig 0.16's std.posix no longer wraps fork/exec/dup2, and zmx
// wraps them in its own posix.zig, which is not part of the ipc module we
// import (importing it separately would make a SECOND, incompatible copy of
// every type in it).
extern "c" fn fork() c_int;
extern "c" fn execve(path: [*:0]const u8, argv: [*:null]const ?[*:0]const u8, envp: [*:null]const ?[*:0]const u8) c_int;
extern "c" fn waitpid(pid: c_int, status: ?*c_int, options: c_int) c_int;
extern "c" fn dup2(old_fd: c_int, new_fd: c_int) c_int;
extern "c" fn chdir(path: [*:0]const u8) c_int;
extern "c" fn open(path: [*:0]const u8, flags: c_int, ...) c_int;
extern "c" fn close(fd: c_int) c_int;
extern "c" fn fcntl(fd: c_int, cmd: c_int, arg: c_int) c_int;
extern "c" fn usleep(usec: c_uint) c_int;
extern "c" fn access(path: [*:0]const u8, mode: c_int) c_int;
extern "c" fn _exit(code: c_int) noreturn;

const O_RDWR: c_int = 2;
const F_GETFL: c_int = 3;
const F_SETFL: c_int = 4;
const WNOHANG: c_int = 1;
const F_OK: c_int = 0;

fn setNonBlocking(fd: i32) void {
    const flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return;
    const nonblock: c_int = @intCast(@as(usize, 1) << @bitOffsetOf(std.posix.O, "NONBLOCK"));
    _ = fcntl(fd, F_SETFL, flags | nonblock);
}

/// Is there still a socket at this path? Used only to tell "no such target"
/// apart from "I could not reach one", which connect()'s errno cannot on its
/// own once upstream has folded it into `error.Unexpected`.
fn socketPresent(path: []const u8) bool {
    var buf: [4096]u8 = undefined;
    if (path.len >= buf.len) return false;
    @memcpy(buf[0..path.len], path);
    buf[path.len] = 0;
    return access(@ptrCast(&buf), F_OK) == 0;
}

fn diag(comptime fmt: []const u8, args: anytype) void {
    // stderr ONLY. stdout is frames and nothing else; one stray log line there
    // is a protocol violation the broker reports as a viewer failure.
    var buf: [1024]u8 = undefined;
    const text = std.fmt.bufPrint(&buf, "[zmx-helper] " ++ fmt ++ "\n", args) catch return;
    proto.writeAllBlocking(STDERR, text);
}

// ---------------------------------------------------------------------------
//  Events (helper -> broker)
// ---------------------------------------------------------------------------

const State = struct {
    gpa: std.mem.Allocator,
    io: std.Io,
    zmx_path: []const u8,
    out: proto.OutBuf,
    in: proto.FrameReader = .{},
    /// The attached viewer's socket, or -1. One per process, for its lifetime.
    sock: i32 = -1,
    sock_out: std.ArrayList(u8) = .empty,
    sock_in: ?ipc.SocketBuffer = null,
    /// The `mux.target` label of the attached target (owned).
    target: []u8 = &.{},
    /// The focus generation the daemon last granted US. Every BrokerResize and
    /// BrokerReply quotes it; one quoting an older generation is dropped by
    /// the daemon, which is the point.
    lease_gen: u64 = 0,
    owner: bool = false,
    cols: u16 = 80,
    rows: u16 = 24,
    /// How much of a control frame one `list` chunk may fill. `LIST_CHUNK_BUDGET`
    /// unless `--list-chunk-bytes` lowered it, which only a test does — a
    /// listing big enough to span frames at the real budget is hundreds of live
    /// shells, and that is not a thing a test can stand up.
    list_chunk_budget: usize = LIST_CHUNK_BUDGET,
    /// Set once the daemon has told us the target process ended. Nothing else
    /// may produce an exit event.
    exit_reported: bool = false,
    running: bool = true,
    status: u8 = 0,

    fn event(self: *State, json: []const u8) void {
        self.eventChecked(json) catch |err| {
            diag("dropping event, out of memory: {s}", .{@errorName(err)});
        };
    }

    /// The same, for a caller that must not let a dropped frame pass for a
    /// delivered one. `event` swallows `PayloadTooLong` with a line on stderr,
    /// which for a REPLY to a request means the broker waits out its send
    /// timeout and kills this process — see `cmdList`.
    fn eventChecked(self: *State, json: []const u8) !void {
        try self.out.frame(.control, json);
    }

    fn begin(self: *State, kind: []const u8, key: []const u8) !std.ArrayList(u8) {
        var list: std.ArrayList(u8) = .empty;
        errdefer list.deinit(self.gpa);
        try list.append(self.gpa, '{');
        try proto.appendJsonKey(self.gpa, &list, "v");
        try proto.appendJsonNum(self.gpa, &list, proto.ABI_VERSION);
        try proto.appendJsonKey(self.gpa, &list, key);
        try proto.appendJsonString(self.gpa, &list, kind);
        return list;
    }

    fn ok(self: *State, id: i64) void {
        var list = self.begin("ok", "ev") catch return;
        defer list.deinit(self.gpa);
        proto.appendJsonKey(self.gpa, &list, "id") catch return;
        proto.appendJsonNum(self.gpa, &list, id) catch return;
        list.append(self.gpa, '}') catch return;
        self.event(list.items);
    }

    fn fail(self: *State, id: ?i64, code: []const u8, message: []const u8) void {
        var list = self.begin("error", "ev") catch return;
        defer list.deinit(self.gpa);
        proto.appendJsonKey(self.gpa, &list, "id") catch return;
        if (id) |value| {
            proto.appendJsonNum(self.gpa, &list, value) catch return;
        } else {
            list.appendSlice(self.gpa, "null") catch return;
        }
        proto.appendJsonKey(self.gpa, &list, "code") catch return;
        proto.appendJsonString(self.gpa, &list, code) catch return;
        proto.appendJsonKey(self.gpa, &list, "message") catch return;
        proto.appendJsonString(self.gpa, &list, message) catch return;
        list.append(self.gpa, '}') catch return;
        self.event(list.items);
    }

    fn simple(self: *State, kind: []const u8) void {
        var list = self.begin(kind, "ev") catch return;
        defer list.deinit(self.gpa);
        list.append(self.gpa, '}') catch return;
        self.event(list.items);
    }
};

// ---------------------------------------------------------------------------
//  main
// ---------------------------------------------------------------------------

pub fn main(init: std.process.Init) !void {
    const gpa = init.gpa;

    var zmx_path: []const u8 = "zmx";
    var list_chunk_budget: usize = LIST_CHUNK_BUDGET;
    var args = init.minimal.args.iterate();
    defer args.deinit();
    _ = args.next(); // argv[0]
    while (args.next()) |arg| {
        if (std.mem.eql(u8, arg, "--version-json")) {
            try printVersionJson(init.io);
            return;
        } else if (std.mem.eql(u8, arg, "--zmx")) {
            zmx_path = args.next() orelse {
                diag("--zmx needs a path", .{});
                std.process.exit(2);
            };
        } else if (std.mem.startsWith(u8, arg, "--zmx=")) {
            zmx_path = arg["--zmx=".len..];
        } else if (std.mem.startsWith(u8, arg, "--list-chunk-bytes=")) {
            // TEST SEAM. Lowering the budget is the only way to exercise a
            // multi-frame listing without standing up hundreds of shells; it
            // can only make frames SMALLER, so it cannot be used to produce one
            // the framer would refuse.
            const raw = arg["--list-chunk-bytes=".len..];
            const value = std.fmt.parseInt(usize, raw, 10) catch {
                diag("--list-chunk-bytes needs a number, got {s}", .{raw});
                std.process.exit(2);
            };
            list_chunk_budget = @max(LIST_CHUNK_MIN, @min(value, LIST_CHUNK_BUDGET));
        } else {
            diag("unknown argument: {s}", .{arg});
            std.process.exit(2);
        }
    }

    // A peer that disappears between poll and write must give us EPIPE, not a
    // signal: the broker's stdout, the daemon socket and the child's pipes are
    // all things that can vanish mid-write.
    const ignore: std.posix.Sigaction = .{
        .handler = .{ .handler = std.posix.SIG.IGN },
        .mask = std.posix.sigemptyset(),
        .flags = 0,
    };
    std.posix.sigaction(std.posix.SIG.PIPE, &ignore, null);

    var state = State{
        .gpa = gpa,
        .io = init.io,
        .zmx_path = zmx_path,
        .out = .{ .gpa = gpa },
        .list_chunk_budget = list_chunk_budget,
    };
    defer state.out.deinit();
    defer state.sock_out.deinit(gpa);
    defer if (state.sock_in) |*sb| sb.deinit();
    defer if (state.target.len > 0) gpa.free(state.target);

    setNonBlocking(STDOUT);

    // First frame on the wire: what this binary is. The broker refuses to
    // trust a helper whose ABI is not the one it was built against.
    {
        var list = state.begin("hello", "ev") catch return;
        defer list.deinit(gpa);
        try proto.appendJsonKey(gpa, &list, "abi");
        try proto.appendJsonNum(gpa, &list, proto.ABI_VERSION);
        try proto.appendJsonKey(gpa, &list, "zmx");
        try proto.appendJsonString(gpa, &list, build_options.zmx_commit);
        try proto.appendJsonKey(gpa, &list, "patch");
        try proto.appendJsonString(gpa, &list, build_options.patch_sha256);
        try proto.appendJsonKey(gpa, &list, "helper");
        try proto.appendJsonString(gpa, &list, build_options.helper_version);
        try list.append(gpa, '}');
        state.event(list.items);
    }

    run(&state);

    // Last words matter: a detach reason or an exit the broker has not read
    // yet is the difference between closing a tab and retrying. Drain what we
    // can, bounded, then go.
    var spins: usize = 0;
    while (state.out.pending() > 0 and spins < 300) : (spins += 1) {
        if (!state.out.flush(STDOUT)) break;
        if (state.out.pending() == 0) break;
        _ = usleep(1000);
    }
    if (state.sock >= 0) _ = close(state.sock);
    std.process.exit(state.status);
}

fn printVersionJson(io: std.Io) !void {
    var buf: [1024]u8 = undefined;
    var stdout = std.Io.File.stdout().writer(io, &buf);
    try stdout.interface.print(
        "{{\"abi\":{d},\"helper\":\"{s}\",\"zmx\":\"{s}\",\"patch\":\"{s}\",\"target\":\"{s}\"}}\n",
        .{
            proto.ABI_VERSION,
            build_options.helper_version,
            build_options.zmx_commit,
            build_options.patch_sha256,
            build_options.build_target,
        },
    );
    try stdout.interface.flush();
}

/// The event loop. Reads commands from the broker, output from the daemon,
/// and keeps both write queues draining.
fn run(state: *State) void {
    var stdin_open = true;
    while (state.running) {
        var fds: [3]std.posix.pollfd = undefined;
        var n: usize = 0;
        var stdin_idx: ?usize = null;
        var stdout_idx: ?usize = null;
        var sock_idx: ?usize = null;

        if (stdin_open) {
            fds[n] = .{ .fd = STDIN, .events = std.posix.POLL.IN, .revents = 0 };
            stdin_idx = n;
            n += 1;
        }
        if (state.out.pending() > 0) {
            fds[n] = .{ .fd = STDOUT, .events = std.posix.POLL.OUT, .revents = 0 };
            stdout_idx = n;
            n += 1;
        }
        if (state.sock >= 0) {
            var events: i16 = 0;
            // Stop reading the daemon while our own output is backed up.
            if (state.out.pending() < STDOUT_HIGH_WATER) events |= std.posix.POLL.IN;
            if (state.sock_out.items.len > 0) events |= std.posix.POLL.OUT;
            if (events != 0) {
                fds[n] = .{ .fd = state.sock, .events = events, .revents = 0 };
                sock_idx = n;
                n += 1;
            }
        }
        if (n == 0) break;

        _ = std.posix.poll(fds[0..n], 1000) catch |err| {
            diag("poll failed: {s}", .{@errorName(err)});
            state.status = 3;
            return;
        };

        if (stdout_idx) |i| {
            if (fds[i].revents != 0) {
                if (!state.out.flush(STDOUT)) {
                    // The broker is gone. Nothing we say can be heard, and the
                    // target is none of our business: just leave.
                    diag("broker stdout closed", .{});
                    state.status = 0;
                    return;
                }
            }
        }

        if (sock_idx) |i| {
            if (fds[i].revents & std.posix.POLL.OUT != 0) flushSocket(state);
            if (fds[i].revents & (std.posix.POLL.IN | std.posix.POLL.HUP | std.posix.POLL.ERR) != 0) {
                readSocket(state);
            }
        }

        if (stdin_idx) |i| {
            const flags = std.posix.POLL.IN | std.posix.POLL.HUP | std.posix.POLL.ERR | std.posix.POLL.NVAL;
            if (fds[i].revents & flags != 0) {
                if (!readStdin(state)) {
                    stdin_open = false;
                    // The broker closed our stdin: this viewer is over. The
                    // TARGET is not — we say nothing about it.
                    state.running = false;
                }
            }
        }

        // Opportunistic drain so a quiet loop does not sit on queued bytes.
        if (state.out.pending() > 0) _ = state.out.flush(STDOUT);
        if (state.sock >= 0 and state.sock_out.items.len > 0) flushSocket(state);
    }
}

/// Read and dispatch whatever the broker sent. Returns false at EOF.
fn readStdin(state: *State) bool {
    const room = state.in.writable();
    if (room.len == 0) {
        // Cannot happen: a frame is capped at MAX_PAYLOAD and the buffer holds
        // one whole frame. Treat as protocol failure rather than spinning.
        fatalProtocol(state, "helper input buffer full");
        return false;
    }
    const n = std.posix.read(STDIN, room) catch |err| switch (err) {
        error.WouldBlock => return true,
        else => {
            diag("stdin read failed: {s}", .{@errorName(err)});
            return false;
        },
    };
    if (n == 0) {
        if (!state.in.atBoundary()) fatalProtocol(state, "broker stream ended mid-frame");
        return false;
    }
    state.in.commit(n);

    while (true) {
        const frame = (state.in.next() catch |err| {
            fatalProtocol(state, @errorName(err));
            return false;
        }) orelse break;
        switch (frame.tag) {
            .control => handleControl(state, frame.payload),
            .input => handleInput(state, frame.payload),
            .reply => handleReply(state, frame.payload),
            .output => {
                // Output is ours to produce, never to receive.
                fatalProtocol(state, "broker sent an output frame");
                return false;
            },
            _ => unreachable, // next() rejected it already
        }
        if (!state.running) return false;
    }
    return true;
}

fn fatalProtocol(state: *State, message: []const u8) void {
    diag("protocol failure: {s}", .{message});
    state.fail(null, "protocol", message);
    state.running = false;
    state.status = 2;
}

// ---------------------------------------------------------------------------
//  Commands
// ---------------------------------------------------------------------------

fn handleControl(state: *State, payload: []const u8) void {
    const parsed = std.json.parseFromSlice(std.json.Value, state.gpa, payload, .{}) catch {
        return fatalProtocol(state, "control frame is not JSON");
    };
    defer parsed.deinit();
    const obj = switch (parsed.value) {
        .object => |o| o,
        else => return fatalProtocol(state, "control frame is not a JSON object"),
    };

    const version: i64 = switch (obj.get("v") orelse .null) {
        .integer => |i| i,
        else => -1,
    };
    if (version != proto.ABI_VERSION) {
        return fatalProtocol(state, "control frame speaks another protocol version");
    }

    const id: ?i64 = switch (obj.get("id") orelse .null) {
        .integer => |i| i,
        else => null,
    };
    const op = switch (obj.get("op") orelse .null) {
        .string => |s| s,
        else => return fatalProtocol(state, "control frame has no op"),
    };

    if (std.mem.eql(u8, op, "create")) {
        cmdCreate(state, obj, id);
    } else if (std.mem.eql(u8, op, "attach")) {
        cmdAttach(state, obj, id);
    } else if (std.mem.eql(u8, op, "list")) {
        cmdList(state, obj, id);
    } else if (std.mem.eql(u8, op, "focus")) {
        cmdFocus(state, obj, id);
    } else if (std.mem.eql(u8, op, "resize")) {
        cmdResize(state, obj, id);
    } else if (std.mem.eql(u8, op, "kill")) {
        cmdKill(state, obj, id);
    } else if (std.mem.eql(u8, op, "detach")) {
        cmdDetach(state, id);
    } else {
        state.fail(id, "protocol", "unknown op");
    }
}

fn strField(obj: std.json.ObjectMap, key: []const u8) ?[]const u8 {
    return switch (obj.get(key) orelse .null) {
        .string => |s| s,
        else => null,
    };
}

fn u16Field(obj: std.json.ObjectMap, key: []const u8, fallback: u16) u16 {
    return switch (obj.get(key) orelse .null) {
        .integer => |i| if (i > 0 and i <= 0xffff) @intCast(i) else fallback,
        else => fallback,
    };
}

fn boolField(obj: std.json.ObjectMap, key: []const u8) bool {
    return switch (obj.get(key) orelse .null) {
        .bool => |b| b,
        else => false,
    };
}

/// Create the target if it is not already running. Never attaches.
///
/// The session is created by running the PATCHED `zmx` binary — a session's
/// daemon is a fork of that process, and re-implementing the fork/pty/
/// terminal-emulator half of it here would be a second copy of the thing we
/// pinned a patch to. argv is a real vector and there is no shell command
/// string anywhere in it.
fn cmdCreate(state: *State, obj: std.json.ObjectMap, id: ?i64) void {
    const name = strField(obj, "name") orelse return state.fail(id, "protocol", "create needs name");
    const socket_path = strField(obj, "socket") orelse return state.fail(id, "protocol", "create needs socket");
    const cwd = strField(obj, "cwd") orelse return state.fail(id, "protocol", "create needs cwd");

    // Already there? Then this is a no-op — but only once we have PROVED it is
    // the same target. A socket basename is a hash, and creating "into"
    // somebody else's session would be exactly the collision we refuse.
    if (ipc.connectSession(socket_path)) |fd| {
        defer _ = close(fd);
        verifyTarget(state.gpa, fd, name, 2000) catch |err| {
            return state.fail(id, mapVerifyError(err), @errorName(err));
        };
        return state.ok(id orelse 0);
    } else |_| {}

    spawnTarget(state, obj, name, socket_path, cwd) catch |err| {
        return state.fail(id, "backend-unavailable", @errorName(err));
    };

    // Wait for the target to be BOTH reachable and labelled.
    //
    // Reachable comes first and means less than it looks: `zmx attach` binds
    // and listens in the client process before it forks the daemon, so a
    // connect can land in the backlog of a socket nobody is accepting on yet,
    // and the label is written by a second connection after that. So every
    // failure short of a genuine mismatch is "not ready yet" and is retried
    // until the deadline; only a wrong `mux.target` is fatal on the spot,
    // because that one cannot become right by waiting.
    var waited: usize = 0;
    var last: []const u8 = "zmx session did not appear";
    while (waited < 10_000) : (waited += 100) {
        if (ipc.connectSession(socket_path)) |fd| {
            defer _ = close(fd);
            if (verifyTarget(state.gpa, fd, name, 500)) {
                return state.ok(id orelse 0);
            } else |err| {
                if (err == error.TargetMismatch) {
                    return state.fail(id, "protocol", @errorName(err));
                }
                last = @errorName(err);
            }
        } else |_| {}
        _ = usleep(100 * 1000);
    }
    state.fail(id, "backend-unavailable", last);
}

fn spawnTarget(
    state: *State,
    obj: std.json.ObjectMap,
    name: []const u8,
    socket_path: []const u8,
    cwd: []const u8,
) !void {
    const gpa = state.gpa;
    const dir = std.fs.path.dirname(socket_path) orelse return error.BadSocketPath;
    const basename = std.fs.path.basename(socket_path);

    // ---- argv: zmx attach --labels mux.target=<name> <basename> <argv...> --
    var argv: std.ArrayList([:0]const u8) = .empty;
    defer {
        for (argv.items) |a| gpa.free(a);
        argv.deinit(gpa);
    }
    try argv.append(gpa, try gpa.dupeZ(u8, state.zmx_path));
    try argv.append(gpa, try gpa.dupeZ(u8, "attach"));
    try argv.append(gpa, try gpa.dupeZ(u8, "--labels"));
    // The label is applied by the CLI as the session is created, so a target
    // is never briefly unlabelled — an unlabelled session is one `list` can
    // never map back to a workspace and no attach will ever accept.
    try argv.append(gpa, try std.fmt.allocPrintSentinel(gpa, "{s}={s}", .{ TARGET_LABEL, name }, 0));
    try argv.append(gpa, try gpa.dupeZ(u8, basename));
    if (obj.get("argv")) |value| {
        switch (value) {
            .array => |items| for (items.items) |item| switch (item) {
                .string => |s| try argv.append(gpa, try gpa.dupeZ(u8, s)),
                else => return error.BadArgv,
            },
            else => return error.BadArgv,
        }
    }

    // ---- env: exactly what the broker asked for --------------------------
    //
    // Nothing is inherited from this process. The zmx CLI's own control
    // variables are dropped whatever the broker sent, because ZMX_SESSION
    // inherited from a shell we happen to be running under would make `attach`
    // switch sessions instead of creating one, and ZMX_SESSION_PREFIX would
    // silently rename the socket we are about to look for.
    //
    // The match is on the PREFIX, not a list of names: upstream adds ZMX_*
    // knobs between pins, and a new one would otherwise be honoured silently
    // by a helper built from a bumped pin. `ZMX_DIR` is re-added below, as the
    // one variable we set ourselves.
    const DROPPED_PREFIX = "ZMX_";
    var envp: std.ArrayList([:0]const u8) = .empty;
    defer {
        for (envp.items) |e| gpa.free(e);
        envp.deinit(gpa);
    }
    if (obj.get("env")) |value| {
        switch (value) {
            .object => |env_obj| {
                var it = env_obj.iterator();
                while (it.next()) |entry| {
                    const key = entry.key_ptr.*;
                    if (std.mem.startsWith(u8, key, DROPPED_PREFIX)) continue;
                    const val = switch (entry.value_ptr.*) {
                        .string => |s| s,
                        else => continue,
                    };
                    try envp.append(gpa, try std.fmt.allocPrintSentinel(gpa, "{s}={s}", .{ key, val }, 0));
                }
            },
            else => return error.BadEnv,
        }
    }
    // The one variable we set ourselves: which directory the socket lives in.
    try envp.append(gpa, try std.fmt.allocPrintSentinel(gpa, "ZMX_DIR={s}", .{dir}, 0));

    const argv_z = try gpa.allocSentinel(?[*:0]const u8, argv.items.len, null);
    defer gpa.free(argv_z);
    for (argv.items, 0..) |a, i| argv_z[i] = a.ptr;
    const envp_z = try gpa.allocSentinel(?[*:0]const u8, envp.items.len, null);
    defer gpa.free(envp_z);
    for (envp.items, 0..) |e, i| envp_z[i] = e.ptr;

    const cwd_z = try gpa.dupeZ(u8, cwd);
    defer gpa.free(cwd_z);
    const exe_z = try gpa.dupeZ(u8, state.zmx_path);
    defer gpa.free(exe_z);

    const pid = fork();
    if (pid < 0) return error.ForkFailed;
    if (pid == 0) {
        if (chdir(cwd_z.ptr) != 0) _exit(126);
        // The zmx CLI half of this child is a client that immediately sees EOF
        // on stdin and detaches; the daemon it leaves behind has already
        // redirected its own stdio. Nothing it prints belongs on OUR stdout.
        const devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            _ = dup2(devnull, 0);
            _ = dup2(devnull, 1);
            _ = dup2(devnull, 2);
            if (devnull > 2) _ = close(devnull);
        }
        _ = execve(exe_z.ptr, argv_z.ptr, envp_z.ptr);
        _exit(127);
    }

    // Reap the client half. The daemon is double-forked and is NOT this child.
    var waited: usize = 0;
    while (waited < 10_000) : (waited += 20) {
        var status: c_int = 0;
        const rc = waitpid(pid, &status, WNOHANG);
        if (rc == pid) return;
        if (rc < 0) return;
        _ = usleep(20 * 1000);
    }
    diag("zmx create client did not exit within 10s", .{});
}

/// Attach this process to an existing target, as one broker viewer.
fn cmdAttach(state: *State, obj: std.json.ObjectMap, id: ?i64) void {
    if (state.sock >= 0) return state.fail(id, "protocol", "already attached");
    const name = strField(obj, "name") orelse return state.fail(id, "protocol", "attach needs name");
    const socket_path = strField(obj, "socket") orelse return state.fail(id, "protocol", "attach needs socket");
    state.cols = u16Field(obj, "cols", state.cols);
    state.rows = u16Field(obj, "rows", state.rows);

    const fd = ipc.connectSession(socket_path) catch |err| {
        // THE SOCKET NOT BEING THERE IS "NO SUCH TARGET", and it has to be said
        // in those words. `ipc.connectSession` collapses every connect failure
        // except ECONNREFUSED into `error.Unexpected` (upstream, ipc.zig), so a
        // target that was closed a moment ago — the exact case the contract
        // calls out, "closed while the attach was in flight" — arrived at the
        // broker as a RECOVERABLE `backend-unavailable`, and a client told to
        // retry a terminal that no longer exists retries for ever.
        //
        // So the errno is not the whole answer: if nothing is listening at that
        // path any more, the target is gone whatever connect() called it. A
        // socket that IS there and refuses is equally gone — the daemon died
        // without unlinking it.
        const gone = err == error.ConnectionRefused or !socketPresent(socket_path);
        return state.fail(id, if (gone) "target-not-found" else "backend-unavailable", @errorName(err));
    };

    // IDENTITY BEFORE ANYTHING ELSE. Until this passes we have not said who we
    // are, so a wrong session is left exactly as we found it — no label
    // written, no BrokerHello, no output forwarded to a viewer that would be
    // rendering another workspace's shell.
    verifyTarget(state.gpa, fd, name, 2000) catch |err| {
        _ = close(fd);
        return state.fail(id, mapVerifyError(err), @errorName(err));
    };

    state.target = state.gpa.dupe(u8, name) catch {
        _ = close(fd);
        return state.fail(id, "backend-unavailable", "out of memory");
    };
    state.sock = fd;
    state.sock_in = ipc.SocketBuffer.init(state.gpa) catch {
        _ = close(fd);
        state.sock = -1;
        return state.fail(id, "backend-unavailable", "out of memory");
    };
    setNonBlocking(fd);

    // Opt in. The name re-registers the label we just verified (a no-op by
    // construction) so a daemon can never end up serving us unlabelled.
    ipc.encodeBrokerHello(state.gpa, &state.sock_out, ipc.BROKER_VERSION_MAX, name) catch {
        return state.fail(id, "backend-unavailable", "out of memory");
    };
    state.ok(id orelse 0);
}

const VerifyError = error{
    TargetMismatch,
    TargetUnlabelled,
    Timeout,
    Disconnected,
};

fn mapVerifyError(err: anyerror) []const u8 {
    return switch (err) {
        error.TargetMismatch, error.TargetUnlabelled => "protocol",
        else => "backend-unavailable",
    };
}

/// `assertTargetMatches`, on the wire: read the daemon's `mux.target` label
/// and compare it with the key we were asked for.
///
/// Called on EVERY attach, and before create adopts an existing session. The
/// socket basename is a 20-hex hash: it can collide, and a collision that is
/// merged rather than detected means two workspaces typing into one shell.
///
/// Messages other than the label are DISCARDED here. That is safe precisely
/// because we have not opted in yet: the restore snapshot the daemon takes at
/// BrokerHello is built from its own terminal, which has already consumed
/// every byte broadcast before this point.
fn verifyTarget(gpa: std.mem.Allocator, fd: i32, name: []const u8, budget_ms: i32) VerifyError!void {
    ipc.send(fd, .LabelGet, "") catch return error.Disconnected;
    var sb = ipc.SocketBuffer.init(gpa) catch return error.Disconnected;
    defer sb.deinit();

    var waited: i32 = 0;
    while (waited < budget_ms) : (waited += 100) {
        var fds = [_]std.posix.pollfd{.{ .fd = fd, .events = std.posix.POLL.IN, .revents = 0 }};
        const ready = std.posix.poll(&fds, 100) catch return error.Disconnected;
        if (ready == 0) continue;
        const n = sb.read(fd) catch |err| switch (err) {
            error.WouldBlock => continue,
            else => return error.Disconnected,
        };
        if (n == 0) return error.Disconnected;
        while (sb.next()) |msg| {
            if (msg.header.tag != .LabelData) continue;
            const value = label.getLabelValueFromPairs(TARGET_LABEL, msg.payload) catch
                return error.TargetUnlabelled;
            if (!std.mem.eql(u8, value, name)) return error.TargetMismatch;
            return;
        }
    }
    return error.Timeout;
}

/// How much of a frame one `list` chunk may fill before it is flushed.
///
/// A listing used to be built as ONE control frame, and `OutBuf.frame` refuses
/// a payload over `MAX_PAYLOAD`: the refusal was logged to stderr and the frame
/// dropped, so the broker's request never got its `ok`, waited out its 20-second
/// send timeout, and killed the helper — in the middle of a close, which is
/// where `list` is used most. The listing is chunked instead. The margin below
/// MAX_PAYLOAD leaves room for the `{"v":1,"ev":…,"id":…,"result":[` envelope
/// and the closing `]}`, so a chunk that fits this bound fits a frame.
const LIST_CHUNK_BUDGET: usize = proto.MAX_PAYLOAD - 1024;

/// The floor `--list-chunk-bytes` may lower the budget to. One row of a real
/// listing is under a kilobyte, so this still admits a single row per chunk.
const LIST_CHUNK_MIN: usize = 64;

/// Enumerate our socket directory and read each session's `mux.target`.
///
/// This — not a file the broker keeps — is how `list` survives a broker
/// restart: the labels live in the running daemons.
///
/// ABSENCE FROM THIS LISTING IS PROOF OF DEATH, upstream. `backend.ts`'s
/// `#confirmClosed`/`#waitUnlisted` decide a target is gone because its row is
/// not here, and `closeScope` closes exactly the rows it is handed. So a
/// listing that is short is not a smaller truth — it is a false one, and it
/// ends with `close()` logging success over a shell that is still running.
/// Every path that can shorten it therefore FAILS the command instead:
///
///   * the directory iterator's error was `catch null`, which ends the loop
///     exactly like the end of the directory;
///   * every `catch continue` on an allocation could drop a row, and the ones
///     after `{` was written would have emitted MALFORMED JSON besides;
///   * an oversized listing was dropped whole, silently (see the budget above).
///
/// A probe that fails is the one thing still skipped, and it is not the same
/// thing: a socket that does not answer the session protocol is a stale file or
/// a stranger's, never a live target of ours.
fn cmdList(state: *State, obj: std.json.ObjectMap, id: ?i64) void {
    const gpa = state.gpa;
    const dir_path = strField(obj, "dir") orelse return state.fail(id, "protocol", "list needs dir");

    var dir = std.Io.Dir.openDirAbsolute(state.io, dir_path, .{ .iterate = true }) catch |err| {
        return state.fail(id, "backend-unavailable", @errorName(err));
    };
    defer dir.close(state.io);
    var iter = dir.iterate();

    // One row at a time, so a row that cannot be built is never half-written
    // into the chunk it would have corrupted.
    var row: std.ArrayList(u8) = .empty;
    defer row.deinit(gpa);
    var chunk = ListChunk.begin(state, id);
    defer chunk.deinit(gpa);

    while (true) {
        const next = iter.next(state.io) catch |err| {
            // A truncated listing read as a complete one is how a close reports
            // success over a live shell. Say so instead.
            return state.fail(id, "backend-unavailable", @errorName(err));
        };
        const entry = next orelse break;
        if (entry.kind != .unix_domain_socket) continue;
        const socket_path = std.fmt.allocPrint(gpa, "{s}/{s}", .{ dir_path, entry.name }) catch
            return state.fail(id, "backend-unavailable", "OutOfMemory");
        defer gpa.free(socket_path);
        const probe = ipc.probeSession(gpa, socket_path) catch continue;
        defer probe.deinit();
        const target: ?[]const u8 = blk: {
            const labels = probe.labels orelse break :blk null;
            break :blk label.getLabelValueFromPairs(TARGET_LABEL, labels) catch null;
        };
        if (target == null) continue; // not ours; never guessed at

        row.clearRetainingCapacity();
        appendListRow(gpa, &row, entry.name, target.?, probe.info) catch
            return state.fail(id, "backend-unavailable", "OutOfMemory");
        chunk.push(gpa, row.items) catch |err|
            return state.fail(id, "backend-unavailable", @errorName(err));
    }

    chunk.finish(gpa) catch |err|
        return state.fail(id, "backend-unavailable", @errorName(err));
}

fn appendListRow(
    gpa: std.mem.Allocator,
    row: *std.ArrayList(u8),
    socket_name: []const u8,
    target: []const u8,
    info: anytype,
) !void {
    try row.append(gpa, '{');
    try proto.appendJsonKey(gpa, row, "socket");
    try proto.appendJsonString(gpa, row, socket_name);
    try proto.appendJsonKey(gpa, row, "name");
    try proto.appendJsonString(gpa, row, target);
    try proto.appendJsonKey(gpa, row, "pid");
    try proto.appendJsonNum(gpa, row, info.pid);
    try proto.appendJsonKey(gpa, row, "createdAt");
    try proto.appendJsonNum(gpa, row, info.created_at);
    try proto.appendJsonKey(gpa, row, "clients");
    try proto.appendJsonNum(gpa, row, info.clients_len);
    try row.append(gpa, '}');
}

/// The listing, emitted as however many frames it takes.
///
/// Rows accumulate until one more would not fit, then go out as a `chunk`
/// event carrying the request id; the LAST batch rides the `ok` that ends the
/// request, so a listing that fits one frame is on the wire exactly as it was
/// before this existed. The broker concatenates chunks in arrival order —
/// `helper.ts` keeps them on the pending entry — and a request that fails part
/// way through never gets its `ok`, so a partial listing is never resolved as
/// a whole one.
const ListChunk = struct {
    state: *State,
    id: ?i64,
    rows: std.ArrayList(u8) = .empty,
    count: usize = 0,

    fn begin(state: *State, id: ?i64) ListChunk {
        return .{ .state = state, .id = id };
    }

    fn deinit(self: *ListChunk, gpa: std.mem.Allocator) void {
        self.rows.deinit(gpa);
    }

    fn push(self: *ListChunk, gpa: std.mem.Allocator, row: []const u8) !void {
        // `+ 1` for the comma this row would need. Flush BEFORE appending, so
        // the frame that goes out is one that was known to fit.
        if (self.count > 0 and self.rows.items.len + row.len + 1 > self.state.list_chunk_budget) {
            try self.flush(gpa, "chunk");
        }
        if (self.count > 0) try self.rows.append(gpa, ',');
        try self.rows.appendSlice(gpa, row);
        self.count += 1;
    }

    fn finish(self: *ListChunk, gpa: std.mem.Allocator) !void {
        try self.flush(gpa, "ok");
    }

    fn flush(self: *ListChunk, gpa: std.mem.Allocator, kind: []const u8) !void {
        var out = try self.state.begin(kind, "ev");
        defer out.deinit(gpa);
        try proto.appendJsonKey(gpa, &out, "id");
        try proto.appendJsonNum(gpa, &out, self.id orelse 0);
        try proto.appendJsonKey(gpa, &out, "result");
        try out.append(gpa, '[');
        try out.appendSlice(gpa, self.rows.items);
        try out.appendSlice(gpa, "]}");
        // Checked: a chunk this process could not put on the wire must not be
        // mistaken for one the broker received, and the `ok` that would have
        // ended the request must not follow it.
        try self.state.eventChecked(out.items);
        self.rows.clearRetainingCapacity();
        self.count = 0;
    }
};

/// Claim or release the focus lease. A claim is ALWAYS explicit: nothing else
/// in this helper can acquire it as a side effect.
fn cmdFocus(state: *State, obj: std.json.ObjectMap, id: ?i64) void {
    if (state.sock < 0) return state.fail(id, "protocol", "not attached");
    const active = boolField(obj, "active");
    state.cols = u16Field(obj, "cols", state.cols);
    state.rows = u16Field(obj, "rows", state.rows);
    const focus = ipc.BrokerFocus{
        .size = .{ .rows = state.rows, .cols = state.cols },
        .active = if (active) 1 else 0,
    };
    ipc.appendMessage(state.gpa, &state.sock_out, .BrokerFocus, std.mem.asBytes(&focus)) catch {
        return state.fail(id, "backend-unavailable", "out of memory");
    };
    if (!active) {
        // A release is acknowledged by us: the daemon answers a CLAIM with a
        // BrokerLease and says nothing on a release.
        state.owner = false;
        state.simple("blur");
    }
    state.ok(id orelse 0);
}

/// Geometry under the generation we hold. Sent unconditionally: whether it
/// takes effect is the DAEMON's decision (owner + live generation), and
/// second-guessing it here would be a second, divergent copy of that rule.
fn cmdResize(state: *State, obj: std.json.ObjectMap, id: ?i64) void {
    if (state.sock < 0) return state.fail(id, "protocol", "not attached");
    state.cols = u16Field(obj, "cols", state.cols);
    state.rows = u16Field(obj, "rows", state.rows);
    const resize = ipc.BrokerResize{
        .gen = state.lease_gen,
        .size = .{ .rows = state.rows, .cols = state.cols },
    };
    ipc.appendMessage(state.gpa, &state.sock_out, .BrokerResize, std.mem.asBytes(&resize)) catch {
        return state.fail(id, "backend-unavailable", "out of memory");
    };
    state.ok(id orelse 0);
}

/// Destroy a target. Explicit, and never a side effect of this process dying.
///
/// IDENTITY IS MANDATORY HERE. A socket basename is a 20-hex hash of the key,
/// so the path proves nothing about whose daemon is listening on it. Every
/// other path that touches a session (`attach`, and `create` adopting an
/// existing one) verifies `mux.target` before it acts; a kill that did not
/// would be the one operation able to destroy a colliding workspace's shell.
/// So `name` is required, and a session we cannot match is left alone.
fn cmdKill(state: *State, obj: std.json.ObjectMap, id: ?i64) void {
    const socket_path = strField(obj, "socket") orelse return state.fail(id, "protocol", "kill needs socket");
    const name = strField(obj, "name") orelse return state.fail(id, "protocol", "kill needs name");
    const fd = ipc.connectSession(socket_path) catch |err| {
        // Already gone is success: kill is idempotent.
        if (err == error.ConnectionRefused) return state.ok(id orelse 0);
        return state.fail(id, "backend-unavailable", @errorName(err));
    };
    defer _ = close(fd);
    verifyTarget(state.gpa, fd, name, 2000) catch |err| {
        return state.fail(id, mapVerifyError(err), @errorName(err));
    };
    ipc.send(fd, .Kill, "") catch |err| {
        return state.fail(id, "backend-unavailable", @errorName(err));
    };
    state.ok(id orelse 0);
}

/// Drop this viewer. The target survives; the helper exits once the queue is
/// drained.
fn cmdDetach(state: *State, id: ?i64) void {
    if (state.sock >= 0) {
        ipc.send(state.sock, .Detach, "") catch {};
        _ = close(state.sock);
        state.sock = -1;
    }
    state.ok(id orelse 0);
    state.running = false;
    state.status = 0;
}

// ---------------------------------------------------------------------------
//  Data frames from the broker
// ---------------------------------------------------------------------------

/// User keystrokes. They always reach the pty and NEVER move the focus lease
/// — that is the whole reason this is a separate tag from BrokerReply, and
/// from upstream's "whoever types becomes the leader" heuristic.
fn handleInput(state: *State, payload: []const u8) void {
    if (state.sock < 0) return;
    ipc.appendMessage(state.gpa, &state.sock_out, .BrokerInput, payload) catch {
        diag("dropping input, out of memory", .{});
    };
}

/// A terminal REPLY the viewer's own emulator produced.
///
/// OWNER ONLY. The patched daemon DISCARDS a non-owner's reply, because
/// several viewers each render the same DA1 query and each answers it, and
/// every answer after the first is read by the shell as typed input. So a
/// background tab's answer is dropped HERE too — sending it would only make
/// the daemon drop it, and would let this side pretend it had landed.
fn handleReply(state: *State, payload: []const u8) void {
    if (state.sock < 0) return;
    if (!state.owner) return;
    var framed = state.gpa.alloc(u8, ipc.BROKER_REPLY_PREFIX + payload.len) catch {
        diag("dropping reply, out of memory", .{});
        return;
    };
    defer state.gpa.free(framed);
    std.mem.writeInt(u64, framed[0..8], state.lease_gen, @import("builtin").cpu.arch.endian());
    @memcpy(framed[ipc.BROKER_REPLY_PREFIX..], payload);
    ipc.appendMessage(state.gpa, &state.sock_out, .BrokerReply, framed) catch {
        diag("dropping reply, out of memory", .{});
    };
}

// ---------------------------------------------------------------------------
//  The daemon socket
// ---------------------------------------------------------------------------

fn flushSocket(state: *State) void {
    var at: usize = 0;
    const items = state.sock_out.items;
    while (at < items.len) {
        const n = proto.writeSome(state.sock, items[at..]) catch |err| switch (err) {
            error.WouldBlock => break,
            else => {
                lostSocket(state, @errorName(err));
                return;
            },
        };
        if (n == 0) break;
        at += n;
    }
    if (at > 0) {
        const remaining = items.len - at;
        std.mem.copyForwards(u8, state.sock_out.items[0..remaining], items[at..]);
        state.sock_out.items.len = remaining;
    }
}

fn readSocket(state: *State) void {
    var sb = &state.sock_in.?;
    const n = sb.read(state.sock) catch |err| switch (err) {
        error.WouldBlock => return,
        else => return lostSocket(state, @errorName(err)),
    };
    if (n == 0) return lostSocket(state, "daemon closed the connection");

    while (sb.next()) |msg| handleDaemonMessage(state, msg);
}

/// We can no longer see the target. This is NOT an exit: a killed daemon, a
/// crashed daemon and a dropped connection all look like this, and only the
/// daemon's own BrokerExit knows the difference.
fn lostSocket(state: *State, why: []const u8) void {
    if (state.sock >= 0) {
        _ = close(state.sock);
        state.sock = -1;
    }
    var list = state.begin("lost", "ev") catch return;
    defer list.deinit(state.gpa);
    proto.appendJsonKey(state.gpa, &list, "message") catch return;
    proto.appendJsonString(state.gpa, &list, why) catch return;
    list.append(state.gpa, '}') catch return;
    state.event(list.items);
    state.running = false;
    state.status = 3;
}

fn handleDaemonMessage(state: *State, msg: ipc.SocketMsg) void {
    const gpa = state.gpa;
    switch (msg.header.tag) {
        .Output => state.out.output(msg.payload) catch diag("dropping output, out of memory", .{}),

        .BrokerWelcome => {
            if (msg.payload.len < @sizeOf(ipc.BrokerWelcome)) return;
            const welcome = std.mem.bytesToValue(ipc.BrokerWelcome, msg.payload[0..@sizeOf(ipc.BrokerWelcome)]);
            var list = state.begin("welcome", "ev") catch return;
            defer list.deinit(gpa);
            proto.appendJsonKey(gpa, &list, "version") catch return;
            proto.appendJsonNum(gpa, &list, welcome.version) catch return;
            // A u64 watermark goes out as a STRING: it can exceed 2^53 and
            // JSON numbers are doubles on the other end.
            proto.appendJsonKey(gpa, &list, "leaseGen") catch return;
            var buf: [24]u8 = undefined;
            const text = std.fmt.bufPrint(&buf, "{d}", .{welcome.lease_gen}) catch return;
            proto.appendJsonString(gpa, &list, text) catch return;
            proto.appendJsonKey(gpa, &list, "pendingMax") catch return;
            proto.appendJsonNum(gpa, &list, welcome.pending_max) catch return;
            proto.appendJsonKey(gpa, &list, "snapshotMax") catch return;
            proto.appendJsonNum(gpa, &list, welcome.snapshot_max) catch return;
            list.append(gpa, '}') catch return;
            state.event(list.items);
        },

        .BrokerLease => {
            if (msg.payload.len < @sizeOf(ipc.BrokerLease)) return;
            const lease = std.mem.bytesToValue(ipc.BrokerLease, msg.payload[0..@sizeOf(ipc.BrokerLease)]);
            state.lease_gen = lease.gen;
            state.owner = true;
            var list = state.begin("lease", "ev") catch return;
            defer list.deinit(gpa);
            proto.appendJsonKey(gpa, &list, "gen") catch return;
            var buf: [24]u8 = undefined;
            const text = std.fmt.bufPrint(&buf, "{d}", .{lease.gen}) catch return;
            proto.appendJsonString(gpa, &list, text) catch return;
            proto.appendJsonKey(gpa, &list, "cols") catch return;
            proto.appendJsonNum(gpa, &list, lease.size.cols) catch return;
            proto.appendJsonKey(gpa, &list, "rows") catch return;
            proto.appendJsonNum(gpa, &list, lease.size.rows) catch return;
            list.append(gpa, '}') catch return;
            state.event(list.items);
        },

        .BrokerReplayStart, .BrokerReplayEnd => {
            if (msg.payload.len < @sizeOf(ipc.BrokerReplay)) return;
            const replay = std.mem.bytesToValue(ipc.BrokerReplay, msg.payload[0..@sizeOf(ipc.BrokerReplay)]);
            const kind = if (msg.header.tag == .BrokerReplayStart) "replay-start" else "replay-end";
            var list = state.begin(kind, "ev") catch return;
            defer list.deinit(gpa);
            proto.appendJsonKey(gpa, &list, "epoch") catch return;
            var buf: [24]u8 = undefined;
            const text = std.fmt.bufPrint(&buf, "{d}", .{replay.epoch}) catch return;
            proto.appendJsonString(gpa, &list, text) catch return;
            proto.appendJsonKey(gpa, &list, "bytes") catch return;
            proto.appendJsonNum(gpa, &list, replay.bytes) catch return;
            list.append(gpa, '}') catch return;
            state.event(list.items);
        },

        .BrokerExit => {
            if (msg.payload.len < @sizeOf(ipc.BrokerExit)) return;
            if (state.exit_reported) return; // at most once, like the daemon
            state.exit_reported = true;
            const exit = std.mem.bytesToValue(ipc.BrokerExit, msg.payload[0..@sizeOf(ipc.BrokerExit)]);
            var list = state.begin("exit", "ev") catch return;
            defer list.deinit(gpa);
            proto.appendJsonKey(gpa, &list, "known") catch return;
            list.appendSlice(gpa, if (exit.known != 0) "true" else "false") catch return;
            proto.appendJsonKey(gpa, &list, "code") catch return;
            // `known == 0` means the daemon saw the pty close but could not
            // reap a status. Reporting 0 there would claim a clean exit we
            // never observed, so it goes out as null.
            if (exit.known != 0 and exit.signaled == 0) {
                proto.appendJsonNum(gpa, &list, exit.code) catch return;
            } else {
                list.appendSlice(gpa, "null") catch return;
            }
            proto.appendJsonKey(gpa, &list, "signal") catch return;
            if (exit.known != 0 and exit.signaled != 0) {
                proto.appendJsonNum(gpa, &list, exit.signal) catch return;
            } else {
                list.appendSlice(gpa, "null") catch return;
            }
            list.append(gpa, '}') catch return;
            state.event(list.items);
        },

        .BrokerFailure, .BrokerDetach => {
            if (msg.payload.len < @sizeOf(ipc.BrokerStatus)) return;
            const status = std.mem.bytesToValue(ipc.BrokerStatus, msg.payload[0..@sizeOf(ipc.BrokerStatus)]);
            const msg_len = @min(status.msg_len, msg.payload.len - @sizeOf(ipc.BrokerStatus));
            const detail = msg.payload[@sizeOf(ipc.BrokerStatus)..][0..msg_len];
            const is_failure = msg.header.tag == .BrokerFailure;
            var list = state.begin(if (is_failure) "failure" else "detached", "ev") catch return;
            defer list.deinit(gpa);
            proto.appendJsonKey(gpa, &list, "code") catch return;
            proto.appendJsonNum(gpa, &list, status.code) catch return;
            proto.appendJsonKey(gpa, &list, if (is_failure) "name" else "reason") catch return;
            proto.appendJsonString(gpa, &list, if (is_failure)
                failureName(status.code)
            else
                detachReason(status.code)) catch return;
            proto.appendJsonKey(gpa, &list, "message") catch return;
            proto.appendJsonString(gpa, &list, detail) catch return;
            list.append(gpa, '}') catch return;
            state.event(list.items);
            if (is_failure) {
                // A refused handshake is terminal for this viewer: the daemon
                // hangs up and nothing we send afterwards is acted on.
                state.running = false;
                state.status = 2;
            }
        },

        // Upstream traffic a broker viewer can meet but has no use for:
        // .Resize is the daemon asking a tty client for its size (we answer
        // with BrokerFocus/BrokerResize instead), .LabelData is the handshake
        // we already consumed, .Ack/.Info are CLI replies.
        else => {},
    }
}

fn failureName(code: u16) []const u8 {
    return switch (code) {
        1 => "unsupported_version",
        2 => "protocol",
        3 => "snapshot_overflow",
        4 => "backend_unavailable",
        else => "unknown",
    };
}

fn detachReason(code: u16) []const u8 {
    return switch (code) {
        1 => "resync_required",
        2 => "daemon_shutdown",
        3 => "snapshot_overflow",
        else => "unknown",
    };
}

test {
    _ = proto;
}

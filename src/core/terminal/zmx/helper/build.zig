//! Build for `mux-zmx-helper`.
//!
//! The helper is compiled against the PATCHED zmx source tree — `-Dzmx-src`
//! points at `build/zmx/upstream/src`, and `ipc.zig` from that tree becomes a
//! module here. The wire types are then the daemon's own definitions by
//! construction: there is no second copy to keep in sync, and a rebase that
//! changes a struct is a compile error here rather than a misread field at
//! runtime.
//!
//! It imports `ipc.zig` and `label.zig` ONLY. Neither pulls in ghostty, so
//! this build does not compile a terminal emulator; session CREATION runs the
//! real `zmx` binary instead of re-implementing the daemon (see main.zig).
//!
//! Driven by scripts/build-zmx.sh --helper; it is not meant to be run by hand,
//! because the pin and patch hash it stamps in come from the lockfile.
//!
//!   zig build --build-file build.zig \
//!     -Dzmx-src=<abs>/build/zmx/upstream/src \
//!     -Dzmx-commit=<sha> -Dpatch-sha256=<sha256> \
//!     --prefix <out>

const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const zmx_src = b.option([]const u8, "zmx-src", "Path to the PATCHED zmx src/ directory") orelse
        @panic("-Dzmx-src=<path to build/zmx/upstream/src> is required");
    const zmx_commit = b.option([]const u8, "zmx-commit", "Pinned zmx commit") orelse "unknown";
    const patch_sha = b.option([]const u8, "patch-sha256", "sha256 of the applied patch") orelse "unknown";
    const helper_version = b.option([]const u8, "helper-version", "Helper build id") orelse "dev";

    const options = b.addOptions();
    options.addOption([]const u8, "zmx_commit", zmx_commit);
    options.addOption([]const u8, "patch_sha256", patch_sha);
    options.addOption([]const u8, "helper_version", helper_version);
    options.addOption([]const u8, "build_target", b.fmt("{s}-{s}", .{
        @tagName(target.result.cpu.arch),
        @tagName(target.result.os.tag),
    }));

    // ipc.zig imports socket.zig/posix.zig/cross.zig relative to itself, so
    // rooting a module at it pulls in exactly the part of zmx that defines the
    // wire — and nothing that needs a terminal emulator.
    const ipc_mod = b.createModule(.{
        .root_source_file = .{ .cwd_relative = b.pathJoin(&.{ zmx_src, "ipc.zig" }) },
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    const label_mod = b.createModule(.{
        .root_source_file = .{ .cwd_relative = b.pathJoin(&.{ zmx_src, "label.zig" }) },
        .target = target,
        .optimize = optimize,
    });

    const exe_mod = b.createModule(.{
        .root_source_file = b.path("main.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    exe_mod.addImport("zmx_ipc", ipc_mod);
    exe_mod.addImport("zmx_label", label_mod);
    exe_mod.addOptions("build_options", options);

    const exe = b.addExecutable(.{ .name = "mux-zmx-helper", .root_module = exe_mod });
    b.installArtifact(exe);

    // The framing tests run against the same code the helper ships, not a
    // transcription of it.
    const test_mod = b.createModule(.{
        .root_source_file = b.path("protocol.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    const unit_tests = b.addTest(.{ .root_module = test_mod });
    const run_tests = b.addRunArtifact(unit_tests);
    b.step("test", "Run helper protocol tests").dependOn(&run_tests.step);
}

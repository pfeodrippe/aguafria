const std = @import("std");

pub fn build(b: *std.Build) void {
    const options = b.addOptions();
    options.addOption(u32, "answer", 42);
    options.addOption([]const u8, "message", "captured by Zig");

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = b.graph.host,
        .optimize = .Debug,
    });
    root_module.addOptions("build_options", options);

    const executable = b.addExecutable(.{
        .name = "build-options-fixture",
        .root_module = root_module,
    });
    b.installArtifact(executable);

    const alternate_options = b.addOptions();
    alternate_options.addOption(u32, "answer", 99);
    alternate_options.addOption([]const u8, "message", "alternate profile");

    const alternate_module = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = b.graph.host,
        .optimize = .Debug,
    });
    alternate_module.addOptions("build_options", alternate_options);

    const alternate_executable = b.addExecutable(.{
        .name = "build-options-fixture-alternate",
        .root_module = alternate_module,
    });
    const alternate = b.step("alternate", "Build with alternate generated options");
    alternate.dependOn(&alternate_executable.step);
}

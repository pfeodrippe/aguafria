const std = @import("std");

pub fn build(b: *std.Build) void {
    const tool_module = b.createModule(.{
        .root_source_file = b.path("src/tool.zig"),
        .target = b.graph.host,
        .optimize = .debug,
    });
    const tool = b.addExecutable(.{
        .name = "build-options-path-tool",
        .root_module = tool_module,
    });

    const options = b.addOptions();
    options.addOption(u32, "answer", 42);
    options.addOption([]const u8, "message", "captured by Zig");
    options.addOption(bool, "use_optional", false);
    options.addOptionPath("data_path", b.path("data.txt"));
    options.addOptionPath("tool_path", tool.getEmittedBin());

    const generated_source = b.addWriteFiles().add(
        "generated_code.zig",
        "pub const answer: u32 = 7;\n",
    );

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = b.graph.host,
        .optimize = .debug,
    });
    root_module.addOptions("build_options", options);
    root_module.addAnonymousImport("generated_code", .{
        .root_source_file = generated_source,
    });

    const executable = b.addExecutable(.{
        .name = "build-options-fixture",
        .root_module = root_module,
    });
    b.installArtifact(executable);

    const alternate_options = b.addOptions();
    alternate_options.addOption(u32, "answer", 99);
    alternate_options.addOption([]const u8, "message", "alternate profile");
    alternate_options.addOption(bool, "use_optional", true);

    const optional_module = b.createModule(.{
        .root_source_file = b.path("src/optional_module.zig"),
        .target = b.graph.host,
        .optimize = .debug,
    });

    const alternate_module = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = b.graph.host,
        .optimize = .debug,
    });
    alternate_module.addOptions("build_options", alternate_options);
    alternate_module.addAnonymousImport("generated_code", .{
        .root_source_file = generated_source,
    });
    alternate_module.addImport("optional", optional_module);

    const alternate_executable = b.addExecutable(.{
        .name = "build-options-fixture-alternate",
        .root_module = alternate_module,
    });
    const alternate = b.step("alternate", "Build with alternate generated options");
    alternate.dependOn(&alternate_executable.step);

    const inspection = b.step("inspection-only", "Inspect without compiling or running the application");
    const forbidden = b.addFail("Application steps must not run during graph inspection");
    executable.step.dependOn(&forbidden.step);
    inspection.dependOn(&b.addRunArtifact(executable).step);

    _ = b.step("empty", "A profile with no generated source");
    const broken_options = b.addOptions();
    broken_options.addOptionPath("missing", b.path("missing-input.txt"));
    const broken_module = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = b.graph.host,
        .optimize = .debug,
    });
    broken_module.addOptions("build_options", broken_options);
    const broken_executable = b.addExecutable(.{
        .name = "broken-producer-fixture",
        .root_module = broken_module,
    });
    b.step("broken-producer", "Report a generated path failure").dependOn(&broken_executable.step);
}

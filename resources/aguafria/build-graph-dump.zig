const std = @import("std");

const Build = std.Build;
const Module = Build.Module;
const Step = Build.Step;

const marker = "__AGUAFRIA_BUILD_OPTION__";
pub const capture_step_name = "__aguafria_capture_build_options";

const Capture = struct {
    owner_path: []const u8,
    import_name: []const u8,
    options: *Step.Options,
};

var captures: std.ArrayListUnmanaged(Capture) = .empty;

/// Select the generated option modules reachable from the requested profile,
/// then replace that profile with one synthetic step that executes only those
/// `Step.Options` nodes and the path-producing dependencies they declared.
pub fn prepare(builder: *Build, target_names: []const []const u8) !void {
    var seen_steps: std.AutoHashMapUnmanaged(*Step, void) = .empty;
    var seen_modules: std.AutoHashMapUnmanaged(*Module, void) = .empty;
    const capture_step = builder.step(capture_step_name, "Resolve Aguafria build option modules");

    if (target_names.len == 0) {
        try visitStep(builder, builder.default_step, capture_step, &seen_steps, &seen_modules);
    } else {
        for (target_names) |target_name| {
            const top_level = builder.top_level_steps.get(target_name) orelse continue;
            try visitStep(builder, &top_level.step, capture_step, &seen_steps, &seen_modules);
        }
    }
}

/// Called by Aguafria's version-matched build runner after the synthetic step
/// succeeds. At this point Zig has appended every `addOptionPath` value to the
/// option module's ordinary Zig source.
pub fn dumpResolved() void {
    for (captures.items) |capture| {
        printHexRecord(capture);
    }
}

fn visitStep(
    builder: *Build,
    step: *Step,
    capture_step: *Step,
    seen_steps: *std.AutoHashMapUnmanaged(*Step, void),
    seen_modules: *std.AutoHashMapUnmanaged(*Module, void),
) !void {
    const entry = try seen_steps.getOrPut(builder.allocator, step);
    if (entry.found_existing) return;

    if (step.id == .compile) {
        const compile: *Step.Compile = @fieldParentPtr("step", step);
        try visitModule(builder, compile.root_module, capture_step, seen_modules);
    }

    for (step.dependencies.items) |dependency| {
        try visitStep(builder, dependency, capture_step, seen_steps, seen_modules);
    }
}

fn visitModule(
    builder: *Build,
    module: *Module,
    capture_step: *Step,
    seen_modules: *std.AutoHashMapUnmanaged(*Module, void),
) !void {
    const entry = try seen_modules.getOrPut(builder.allocator, module);
    if (entry.found_existing) return;

    const owner_path = moduleSourcePath(module) orelse "";
    for (module.import_table.keys(), module.import_table.values()) |import_name, imported| {
        if (optionsData(imported)) |options| {
            try captures.append(builder.allocator, .{
                .owner_path = owner_path,
                .import_name = import_name,
                .options = options.options,
            });
            capture_step.dependOn(&options.options.step);
        } else {
            try visitModule(builder, imported, capture_step, seen_modules);
        }
    }
}

fn moduleSourcePath(module: *Module) ?[]const u8 {
    const source = module.root_source_file orelse return null;
    return switch (source) {
        .src_path => |path| path.sub_path,
        .cwd_relative => |path| path,
        .dependency => |path| path.sub_path,
        .generated => null,
    };
}

const OptionsData = struct {
    options: *Step.Options,
};

fn optionsData(module: *Module) ?OptionsData {
    const source = module.root_source_file orelse return null;
    const generated = switch (source) {
        .generated => |value| value,
        else => return null,
    };
    if (generated.file.step.id != .options) return null;
    const options: *Step.Options = @fieldParentPtr("step", generated.file.step);
    return .{ .options = options };
}

fn printHexRecord(capture: Capture) void {
    std.debug.print("{s}\t", .{marker});
    printHex(capture.owner_path);
    std.debug.print("\t", .{});
    printHex(capture.import_name);
    std.debug.print("\t", .{});
    printHex(capture.options.contents.items);
    std.debug.print("\t{d}", .{capture.options.args.items.len});
    for (capture.options.args.items) |arg| {
        std.debug.print("\t", .{});
        printHex(arg.name);
        std.debug.print("\t", .{});
        printHex(pathKind(arg.path));
        std.debug.print("\t", .{});
        printHex(arg.path.getPath2(capture.options.step.owner, &capture.options.step));
        std.debug.print("\t", .{});
        printHex(resolvedPath(arg.path, capture.options.step.owner));
    }
    std.debug.print("\n", .{});
}

fn resolvedPath(path: std.Build.LazyPath, builder: *Build) []const u8 {
    return switch (path) {
        .src_path => |source| source.owner.pathResolve(&.{
            source.owner.build_root.path orelse ".",
            source.sub_path,
        }),
        .cwd_relative, .dependency => path.getPath2(builder, builder.default_step),
        .generated => |generated| blk: {
            var result = generated.file.path orelse @panic("generated path did not run");
            if (!std.fs.path.isAbsolute(result)) {
                result = std.fs.path.resolve(builder.allocator, &.{
                    generated.file.step.owner.build_root.path orelse ".",
                    result,
                }) catch @panic("OOM");
            }
            for (0..generated.up) |_| {
                result = std.fs.path.dirname(result) orelse
                    @panic("generated path escaped its cache root");
            }
            break :blk std.fs.path.resolve(builder.allocator, &.{
                result,
                generated.sub_path,
            }) catch @panic("OOM");
        },
    };
}

fn pathKind(path: std.Build.LazyPath) []const u8 {
    return switch (path) {
        .src_path => "project",
        .generated => "generated",
        .cwd_relative => "host",
        .dependency => "dependency",
    };
}

fn printHex(bytes: []const u8) void {
    for (bytes) |byte| std.debug.print("{x:0>2}", .{byte});
}

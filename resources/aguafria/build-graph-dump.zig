const std = @import("std");

const Build = std.Build;
const Module = Build.Module;
const Step = Build.Step;

const marker = "__AGUAFRIA_BUILD_OPTION__";

pub fn dump(builder: *Build, target_names: []const []const u8) !void {
    var seen_steps: std.AutoHashMapUnmanaged(*Step, void) = .empty;
    var seen_modules: std.AutoHashMapUnmanaged(*Module, void) = .empty;

    if (target_names.len == 0) {
        try visitStep(builder, builder.default_step, &seen_steps, &seen_modules);
    } else {
        for (target_names) |target_name| {
            const top_level = builder.top_level_steps.get(target_name) orelse continue;
            try visitStep(builder, &top_level.step, &seen_steps, &seen_modules);
        }
    }
}

fn visitStep(
    builder: *Build,
    step: *Step,
    seen_steps: *std.AutoHashMapUnmanaged(*Step, void),
    seen_modules: *std.AutoHashMapUnmanaged(*Module, void),
) !void {
    const entry = try seen_steps.getOrPut(builder.allocator, step);
    if (entry.found_existing) return;

    if (step.id == .compile) {
        const compile: *Step.Compile = @fieldParentPtr("step", step);
        try visitModule(builder, compile.root_module, seen_modules);
    }

    for (step.dependencies.items) |dependency| {
        try visitStep(builder, dependency, seen_steps, seen_modules);
    }
}

fn visitModule(
    builder: *Build,
    module: *Module,
    seen_modules: *std.AutoHashMapUnmanaged(*Module, void),
) !void {
    const entry = try seen_modules.getOrPut(builder.allocator, module);
    if (entry.found_existing) return;

    const owner_path = moduleSourcePath(module) orelse "";
    for (module.import_table.keys(), module.import_table.values()) |import_name, imported| {
        if (optionsData(imported)) |options| {
            printHexRecord(owner_path, import_name, options.contents, options.path_count);
        } else {
            try visitModule(builder, imported, seen_modules);
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
    contents: []const u8,
    path_count: usize,
};

fn optionsData(module: *Module) ?OptionsData {
    const source = module.root_source_file orelse return null;
    const generated = switch (source) {
        .generated => |value| value,
        else => return null,
    };
    if (generated.file.step.id != .options) return null;
    const options: *Step.Options = @fieldParentPtr("step", generated.file.step);
    return .{
        .contents = options.contents.items,
        .path_count = options.args.items.len,
    };
}

fn printHexRecord(
    owner_path: []const u8,
    import_name: []const u8,
    contents: []const u8,
    path_count: usize,
) void {
    std.debug.print("{s}\t", .{marker});
    printHex(owner_path);
    std.debug.print("\t", .{});
    printHex(import_name);
    std.debug.print("\t", .{});
    printHex(contents);
    std.debug.print("\t{d}\n", .{path_count});
}

fn printHex(bytes: []const u8) void {
    for (bytes) |byte| std.debug.print("{x:0>2}", .{byte});
}

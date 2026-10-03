//! Inspect Zig's serialized build configuration through its build-server protocol.
//! Build only the producers of imported generated source and option paths.
const std = @import("std");
const Config = std.Build.Configuration;
const Message = std.zig.Server.Message;
const Io = std.Io;
const Allocator = std.mem.Allocator;

const Capture = struct {
    owner: ?Config.LazyPath.Index,
    name: []const u8,
    source: Config.LazyPath.Index,
};

const Inspector = struct {
    arena: Allocator,
    io: Io,
    config: Config,
    zig: []const u8,
    cwd: []const u8,
    build_root: []const u8,
    local_cache: []const u8,
    global_cache: []const u8,
    zig_lib: []const u8,
    install_prefix: []const u8,
    producers: []?Config.Step.Index,
    generated: []?[]const u8,
    selected: std.AutoArrayHashMapUnmanaged(Config.Step.Index, void) = .empty,
    seen_steps: std.AutoHashMapUnmanaged(Config.Step.Index, void) = .empty,
    seen_modules: std.AutoHashMapUnmanaged(Config.Module.Index, void) = .empty,
    captures: std.ArrayList(Capture) = .empty,

    fn registerOutput(self: *Inspector, step: Config.Step.Index, output: ?Config.GeneratedFileIndex) void {
        if (output) |index| self.producers[@backingInt(index)] = step;
    }

    fn indexProducers(self: *Inspector) void {
        for (self.config.steps, 0..) |step, i| {
            const index: Config.Step.Index = @fromBackingInt(@intCast(i));
            switch (step.extended.get(self.config.extra)) {
                .compile => |v| inline for (.{ "emit_directory", "generated_docs", "generated_asm", "generated_bin", "generated_pdb", "generated_implib", "generated_llvm_bc", "generated_llvm_ir", "generated_h" }) |field| {
                    self.registerOutput(index, @field(v, field).value);
                },
                .config_header => |v| self.registerOutput(index, v.generated_dir),
                .find_program => |v| self.registerOutput(index, v.found_path),
                .obj_copy => |v| {
                    self.registerOutput(index, v.output_file);
                    self.registerOutput(index, v.debug_file.value);
                },
                .options => |v| self.registerOutput(index, v.generated_file),
                .run => |v| {
                    if (v.captured_stdout.value) |o| self.registerOutput(index, o.generated_file);
                    if (v.captured_stderr.value) |o| self.registerOutput(index, o.generated_file);
                    for (v.args.slice) |arg| self.registerOutput(index, arg.get(&self.config).generated.value);
                },
                .translate_c => |v| self.registerOutput(index, v.output_file),
                .write_file => |v| self.registerOutput(index, v.generated_directory),
                else => {},
            }
        }
    }

    fn requirePath(self: *Inspector, path: Config.LazyPath.Index) !void {
        switch (path.get(&self.config)) {
            .generated => |g| try self.selected.put(self.arena, self.producers[@backingInt(g.index)] orelse return error.MissingGeneratedFileProducer, {}),
            else => {},
        }
    }

    fn visitModule(self: *Inspector, index: Config.Module.Index) !void {
        if ((try self.seen_modules.getOrPut(self.arena, index)).found_existing) return;
        const module = index.get(&self.config);
        const imports = module.import_table.get(&self.config).imports.mal;
        for (imports.items(.name), imports.items(.module)) |name, imported_index| {
            const imported = imported_index.get(&self.config);
            if (imported.root_source_file.unwrap()) |source| {
                try self.requirePath(source);
                if (module.root_source_file.unwrap()) |owner| try self.requirePath(owner);
                try self.captures.append(self.arena, .{
                    .owner = module.root_source_file.unwrap(),
                    .name = name.slice(&self.config),
                    .source = source,
                });
            }
            try self.visitModule(imported_index);
        }
    }

    fn visitStep(self: *Inspector, index: Config.Step.Index) !void {
        if ((try self.seen_steps.getOrPut(self.arena, index)).found_existing) return;
        const step = index.ptr(&self.config);
        switch (step.extended.get(self.config.extra)) {
            .compile => |v| try self.visitModule(v.root_module),
            else => {},
        }
        for (step.deps.slice(&self.config)) |dep| try self.visitStep(dep);
    }

    fn select(self: *Inspector, names: []const []const u8) !void {
        self.indexProducers();
        if (names.len == 0) return self.visitStep(self.config.default_step);
        for (names) |name| {
            for (self.config.steps, 0..) |step, i| {
                if (step.extended.get(self.config.extra) == .top_level and
                    std.mem.eql(u8, step.name.slice(&self.config), name))
                {
                    try self.visitStep(@fromBackingInt(@intCast(i)));
                    break;
                }
            } else {
                std.debug.print("Unknown Zig build step: {s}\n", .{name});
                return error.UnknownBuildStep;
            }
        }
    }

    fn prefix(self: *Inspector, value: Message.PathPrefix) []const u8 {
        return switch (value) {
            .cwd => self.cwd,
            .zig_lib => self.zig_lib,
            .local_cache => self.local_cache,
            .global_cache => self.global_cache,
            .build_root => self.build_root,
        };
    }

    fn resolve(self: *Inspector, index: Config.LazyPath.Index) ![]const u8 {
        const conf = &self.config;
        return switch (index.get(conf)) {
            .source_path => |p| std.fs.path.resolveAlloc(self.arena, &.{
                self.build_root,
                if (p.owner.get(conf)) |pkg| pkg.root_path.slice(conf) else "",
                p.sub_path.slice(conf),
            }),
            .generated => |p| path: {
                var base = self.generated[@backingInt(p.index)] orelse return error.GeneratedPathUnavailable;
                for (0..p.flags.up) |_| base = std.fs.path.dirname(base) orelse return error.GeneratedPathEscapedRoot;
                break :path std.fs.path.resolveAlloc(self.arena, &.{ base, p.sub_path.slice(conf) });
            },
            .relative => |p| path: {
                const base = switch (p.flags.base) {
                    .cwd => self.cwd,
                    .local_cache => self.local_cache,
                    .global_cache => self.global_cache,
                    .build_root => self.build_root,
                    .zig_exe => self.zig,
                    .zig_lib => self.zig_lib,
                    .install_prefix => self.install_prefix,
                    .install_lib => try std.fs.path.join(self.arena, &.{ self.install_prefix, "lib" }),
                    .install_bin => try std.fs.path.join(self.arena, &.{ self.install_prefix, "bin" }),
                    .install_include => try std.fs.path.join(self.arena, &.{ self.install_prefix, "include" }),
                    .libc_runtimes => return error.LibcRuntimePathUnavailable,
                };
                break :path std.fs.path.resolveAlloc(self.arena, &.{ base, p.sub_path.slice(conf) });
            },
        };
    }

    fn relativeOwner(self: *Inspector, owner: ?Config.LazyPath.Index) ![]const u8 {
        return if (owner) |path|
            std.fs.path.relativeAlloc(self.arena, self.cwd, null, self.cwd, try self.resolve(path))
        else
            "";
    }

    fn options(self: *Inspector, path: Config.LazyPath.Index) ?Config.Step.Options {
        const g = switch (path.get(&self.config)) {
            .generated => |g| g,
            else => return null,
        };
        const producer = self.producers[@backingInt(g.index)] orelse return null;
        return switch (producer.ptr(&self.config).extended.get(self.config.extra)) {
            .options => |v| v,
            else => null,
        };
    }

    fn optionValue(self: *Inspector, tree: *const std.zig.Ast, name: []const u8) ![]const u8 {
        for (tree.rootDecls()) |node| {
            const decl = tree.fullVarDecl(node) orelse continue;
            const token = tree.tokenSlice(decl.ast.mut_token + 1);
            const identifier = if (std.mem.startsWith(u8, token, "@\""))
                try std.zig.string_literal.parseAlloc(self.arena, token[1..])
            else
                token;
            if (!std.mem.eql(u8, identifier, name)) continue;
            const value = decl.ast.init_node.unwrap() orelse return error.MissingOptionValue;
            if (tree.nodeTag(value) != .string_literal) return error.ExpectedOptionPathString;
            return std.zig.string_literal.parseAlloc(self.arena, tree.tokenSlice(tree.firstToken(value)));
        }
        return error.MissingOptionPathDeclaration;
    }

    fn printPath(self: *Inspector, tree: *const std.zig.Ast, path: Config.Step.Options.NamedPath) !void {
        const resolved = try self.resolve(path.path);
        const kind: []const u8 = switch (path.path.get(&self.config)) {
            .source_path => |p| if (p.owner == .root) "project" else "dependency",
            .generated => "generated",
            .relative => "host",
        };
        printField(path.name.slice(&self.config));
        printField(kind);
        printField(try self.optionValue(tree, path.name.slice(&self.config)));
        printField(resolved);
    }

    fn dump(self: *Inspector) !void {
        for (self.captures.items) |capture| {
            const source_path = try self.resolve(capture.source);
            if (capture.source.get(&self.config) == .generated) {
                const source = try Io.Dir.cwd().readFileAlloc(self.io, source_path, self.arena, .unlimited);
                std.debug.print("__AGUAFRIA_BUILD_OPTION__", .{});
                printField(try self.relativeOwner(capture.owner));
                printField(capture.name);
                printField(source);
                if (self.options(capture.source)) |o| {
                    var tree = try std.zig.Ast.parse(self.arena, try self.arena.dupeSentinel(u8, source, 0), .{ .mode = .zig });
                    defer tree.deinit(self.arena);
                    if (tree.errors.len != 0) return error.InvalidGeneratedOptions;
                    std.debug.print("\t{d}", .{o.files.slice.len + o.directories.slice.len + o.untracked_paths.slice.len});
                    for (o.files.slice) |path| try self.printPath(&tree, path);
                    for (o.directories.slice) |path| try self.printPath(&tree, path);
                    for (o.untracked_paths.slice) |path| try self.printPath(&tree, path);
                } else std.debug.print("\t0", .{});
            } else {
                std.debug.print("__AGUAFRIA_BUILD_SOURCE_MODULE__", .{});
                printField(if (capture.owner) |p| try self.resolve(p) else "");
                printField(capture.name);
                printField(source_path);
            }
            std.debug.print("\n", .{});
        }
    }

    fn completedStep(self: *Inspector, body: *Io.Reader) !bool {
        const step = try body.takeStruct(Message.BuildStepCompleted, .little);
        try renderErrors(self.arena, self.io, body, step.error_bundle);
        if (step.status != .success) {
            const declaration = step.step_index.ptr(&self.config);
            std.debug.print("Zig build step '{s}': {t}\n", .{ declaration.name.slice(&self.config), step.status });
            if (step.error_bundle.extra_len == 0) {
                std.debug.print("Zig's build-server protocol supplied no diagnostic text for this step.\n", .{});
                switch (declaration.extended.get(self.config.extra)) {
                    .options => |o| {
                        for (o.files.slice) |p| std.debug.print("  input file: {s}\n", .{try self.resolve(p.path)});
                        for (o.directories.slice) |p| std.debug.print("  input directory: {s}\n", .{try self.resolve(p.path)});
                    },
                    else => {},
                }
            }
        }
        const headers = try self.arena.alloc(Message.GeneratedFile, step.generated_files_len);
        for (headers) |*header| header.* = try body.takeStruct(Message.GeneratedFile, .little);
        for (headers) |header| {
            const base: Message.PathPrefix = @fromBackingInt(try body.takeByte());
            const path = try body.take(header.path_len);
            self.generated[@backingInt(header.index)] = try std.fs.path.resolveAlloc(self.arena, &.{ self.prefix(base), path });
        }
        return step.status == .success;
    }
};

fn printField(bytes: []const u8) void {
    std.debug.print("\t{x}", .{bytes});
}

fn renderErrors(arena: Allocator, io: Io, reader: *Io.Reader, header: Message.ErrorBundle) !void {
    const errors = try std.zig.ErrorBundle.readAlloc(reader, arena, header.extra_len, header.string_bytes_len);
    var buffer: [4096]u8 = undefined;
    var stderr = Io.File.stderr().writerStreaming(io, &buffer);
    try errors.renderToWriter(.{}, &stderr.interface);
    try stderr.interface.flush();
}

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const io = init.io;
    const args = try init.minimal.args.toSlice(arena);
    if (args.len < 5) return error.ExpectedZigBuildFileLibAndGlobalCache;
    const cwd = try std.process.currentPathAlloc(io, arena);
    const build_root = std.fs.path.dirname(args[2]) orelse return error.ExpectedAbsoluteBuildFile;
    // Each build entry point gets its own serialized configuration cache.
    const local_cache = try std.fs.path.join(arena, &.{ build_root, ".zig-cache", "aguafria-build-graph", std.fs.path.basename(args[2]) });
    const install_prefix = if (init.environ_map.get("DESTDIR")) |dest|
        try std.fs.path.join(arena, &.{ dest, "usr" })
    else
        try std.fs.path.join(arena, &.{ build_root, "zig-out" });
    var child = try std.process.spawn(io, .{
        .argv = &.{ args[1], "build", "--build-file", args[2], "--cache-dir", local_cache, "--listen=-" },
        .stdin = .pipe,
        .stdout = .pipe,
    });
    defer child.kill(io);
    var in_buffer: [4096]u8 = undefined;
    var out_buffer: [4096]u8 = undefined;
    var input = child.stdout.?.readerStreaming(io, &in_buffer);
    var output = child.stdin.?.writerStreaming(io, &out_buffer);
    var client: std.zig.Client = .{ .in = &input.interface, .out = &output.interface };
    var inspector: ?Inspector = null;
    var failed = false;
    var handshake = false;
    while (true) {
        const header = try client.receiveMessage();
        const bytes = try client.in.readAlloc(arena, header.bytes_len);
        var body: Io.Reader = .fixed(bytes);
        switch (header.tag) {
            .bsp_handshake => {
                const hello = try body.takeStruct(Message.Handshake, .little);
                if (hello.version != std.zig.Server.build_system_version) return error.BuildProtocolVersionMismatch;
                handshake = true;
            },
            .bsp_configuration => {
                if (!handshake or inspector != null) return error.UnexpectedBuildConfiguration;
                const file = try Io.Dir.cwd().openFile(io, bytes, .{});
                defer file.close(io);
                const conf = try Config.loadFile(arena, io, file);
                inspector = .{
                    .arena = arena,
                    .io = io,
                    .config = conf,
                    .zig = args[1],
                    .cwd = cwd,
                    .build_root = build_root,
                    .local_cache = local_cache,
                    .global_cache = args[4],
                    .zig_lib = args[3],
                    .install_prefix = install_prefix,
                    .producers = try arena.alloc(?Config.Step.Index, conf.generated_files_len),
                    .generated = try arena.alloc(?[]const u8, conf.generated_files_len),
                };
                const graph = &inspector.?;
                @memset(graph.producers, null);
                @memset(graph.generated, null);
                try graph.select(args[5..]);
                if (graph.selected.count() == 0) break;
                try client.serveBuildSteps(graph.selected.keys(), .{ .watch = false });
            },
            .bsp_configuration_failed => {
                try renderErrors(arena, io, &body, try body.takeStruct(Message.ErrorBundle, .little));
                return error.BuildConfigurationFailed;
            },
            .bsp_step_completed => {
                const graph = if (inspector) |*value| value else return error.MissingBuildConfiguration;
                if (!try graph.completedStep(&body)) failed = true;
            },
            .bsp_build_completed => break,
            .bsp_build_started, .bsp_step_started => {},
            else => return error.UnexpectedBuildMessage,
        }
    }
    try client.serveBodylessMessage(.exit);
    if (!(try child.wait(io)).success() or failed) return error.BuildProducerFailed;
    const graph = if (inspector) |*value| value else return error.MissingBuildConfiguration;
    try graph.dump();
}

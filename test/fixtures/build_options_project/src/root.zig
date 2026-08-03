const build_options = @import("build_options");

pub export fn answer() callconv(.c) u32 {
    return build_options.answer;
}

pub export fn data_path_length() callconv(.c) usize {
    return build_options.data_path.len;
}

pub export fn tool_path_length() callconv(.c) usize {
    return build_options.tool_path.len;
}

pub fn main() void {
    _ = build_options.message;
}

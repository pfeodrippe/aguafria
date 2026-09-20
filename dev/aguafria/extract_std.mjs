// Extract Zig's public std declaration graph from the official documentation
// WASM produced by `zig ... -femit-docs`. JSON is written to stdout; diagnostic
// messages from the WASM go to stderr so the Clojure generator can parse it.

import fs from "node:fs";

const [wasmPath, sourcesPath] = process.argv.slice(2);
if (!wasmPath || !sourcesPath) {
  throw new Error("usage: node extract_std.mjs <main.wasm> <sources.tar>");
}

const decoder = new TextDecoder();
let wasm;
const wasmLogs = [];

const instantiated = await WebAssembly.instantiate(
  fs.readFileSync(wasmPath),
  {
    js: {
      log(level, pointer, length) {
        const message = wasm
          ? decoder.decode(new Uint8Array(wasm.memory.buffer, pointer, length))
          : `Zig docs log before initialization (level ${level})`;
        wasmLogs.push({ level, message });
      },
    },
  },
);
wasm = instantiated.instance.exports;

const tar = fs.readFileSync(sourcesPath);
const tarPointer = wasm.alloc(tar.length);
new Uint8Array(wasm.memory.buffer, tarPointer, tar.length).set(tar);
wasm.unpack(tarPointer, tar.length);

function unwrapString(packed) {
  const pointer = Number(packed & 0xffffffffn);
  const length = Number(packed >> 32n);
  if (length === 0) return "";
  return decoder.decode(new Uint8Array(wasm.memory.buffer, pointer, length));
}

function unwrapSlice32(packed) {
  const pointer = Number(packed & 0xffffffffn);
  const length = Number(packed >> 32n);
  if (length === 0) return [];
  return Array.from(new Uint32Array(wasm.memory.buffer, pointer, length));
}

function htmlText(html) {
  return html
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/p\s*>/gi, "\n\n")
    .replace(/<\/div\s*>/gi, "\n")
    .replace(/<\/li\s*>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&#(\d+);/g, (_, value) => String.fromCodePoint(Number(value)))
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

const categories = [
  "namespace",
  "container",
  "global-variable",
  "function",
  "primitive",
  "error-set",
  "global-const",
  "alias",
  "type",
  "type-type",
  "type-function",
];

function resolveDeclaration(original) {
  let declaration = original;
  let category = wasm.categorize_decl(declaration, 0);
  let aliasDepth = 0;
  while (category === 7) {
    declaration = wasm.get_aliasee();
    category = wasm.categorize_decl(declaration, 0);
    aliasDepth += 1;
    if (aliasDepth > 100) {
      throw new Error(`alias cycle at declaration ${original}`);
    }
  }
  return { declaration, category, aliasDepth };
}

function declarationMembers(declaration, category, seen = new Set()) {
  if (seen.has(declaration)) return [];
  seen.add(declaration);
  if (category === 10 || (category === 3 && /\)\s+type$/.test(declarationSignature(declaration, category)))) {
    const members = unwrapSlice32(wasm.type_fn_members(declaration, false));
    if (members.length) return members;
    // The docs resolver handles a bare delegated callee, but not qualified
    // forwarding calls such as `return array_list.Aligned(T, null)`.
    const source = htmlText(unwrapString(wasm.decl_source_html(declaration)));
    const delegated = source.match(/\{\s*return\s+([A-Za-z_][A-Za-z0-9_.]*)\([^;]*\);\s*\}$/s);
    if (delegated) {
      const parent = unwrapString(wasm.decl_fqn(wasm.decl_parent(declaration))).split(".");
      while (parent.length) {
        const query = new TextEncoder().encode([...parent, delegated[1]].join("."));
        const pointer = wasm.set_input_string(query.length);
        new Uint8Array(wasm.memory.buffer, pointer, query.length).set(query);
        const found = wasm.find_decl();
        if (found >= 0 && found !== 0xffffffff) {
          const target = resolveDeclaration(found);
          return declarationMembers(target.declaration, target.category, seen);
        }
        parent.pop();
      }
    }
    return [];
  }
  if (category === 0 || category === 1) {
    return unwrapSlice32(wasm.namespace_members(declaration, false));
  }
  return [];
}

function declarationDocs(original, resolved) {
  const own = htmlText(unwrapString(wasm.decl_docs_html(original, false)));
  if (own) return own;
  return htmlText(unwrapString(wasm.decl_docs_html(resolved, false)));
}

function declarationSignature(declaration, category) {
  if (category === 3 || category === 10) {
    return htmlText(unwrapString(wasm.decl_fn_proto_html(declaration, false)));
  }
  return htmlText(unwrapString(wasm.decl_type_html(declaration)));
}

function zigMember(base, name) {
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) return `${base}.${name}`;
  if (/^@"(?:[^"\\]|\\.)*"$/.test(name)) return `${base}.${name}`;
  return `${base}.@${JSON.stringify(name)}`;
}

const root = wasm.find_module_root(0);
if (root < 0) throw new Error("Zig docs contain no root module");

const namespaces = [];
const traversedPaths = new Set();

function walkNamespace(original, resolved, category, path, zigPath, ancestors) {
  const pathKey = path.join(".");
  if (traversedPaths.has(pathKey)) return;
  traversedPaths.add(pathKey);

  const members = [];
  const childNamespaces = [];
  for (const memberOriginal of declarationMembers(resolved, category)) {
    const name = unwrapString(wasm.decl_name(memberOriginal));
    if (!name) continue;
    const memberResolved = resolveDeclaration(memberOriginal);
    const memberZigPath = zigMember(zigPath, name);
    const childMembers = declarationMembers(
      memberResolved.declaration,
      memberResolved.category,
    );
    const container = childMembers.length > 0;
    const signature = declarationSignature(memberResolved.declaration, memberResolved.category);
    const entry = {
      name,
      "zig-name": memberZigPath,
      category: categories[memberResolved.category] ?? `unknown-${memberResolved.category}`,
      signature,
      documentation: declarationDocs(memberOriginal, memberResolved.declaration),
      source: unwrapString(wasm.decl_file_path(memberResolved.declaration)),
      "param-count":
        memberResolved.category === 3 || memberResolved.category === 10
          ? unwrapSlice32(wasm.decl_params(memberResolved.declaration)).length
          : null,
      "alias-depth": memberResolved.aliasDepth,
      container,
      parameters: (memberResolved.category === 3 || memberResolved.category === 10)
        ? unwrapSlice32(wasm.decl_params(memberResolved.declaration)).map(parameter => {
            const source = htmlText(unwrapString(wasm.decl_param_html(memberResolved.declaration, parameter)));
            const match = source.match(/^(?:(comptime|noalias)\s+)?([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$/s);
            return {name: match?.[2] ?? null, type: match?.[3] ?? source,
              comptime: match?.[1] === "comptime" || (match != null &&
                new RegExp(`\\bcomptime\\s+${match[2]}\\s*:`).test(signature))};
          }) : [],
    };
    const firstParameter = entry.parameters[0];
    if (!entry.parameters.length) delete entry.parameters;
    if ((category === 10 || (category === 3 && /\)\s+type$/.test(declarationSignature(resolved, category))))
        && firstParameter && /^(?:\*\s*(?:const\s+)?)?Self$/.test(firstParameter.type)) {
      entry["receiver-method"] = true;
    }
    members.push(entry);

    if (container && !ancestors.has(memberResolved.declaration)) {
      childNamespaces.push({
        original: memberOriginal,
        resolved: memberResolved.declaration,
        category: memberResolved.category,
        path: [...path, name],
        zigPath: memberZigPath,
        ancestors: new Set([...ancestors, memberResolved.declaration]),
      });
    }
  }

  members.sort((left, right) => left.name.localeCompare(right.name));
  namespaces.push({
    path,
    "zig-name": zigPath,
    documentation: declarationDocs(original, resolved),
    source: unwrapString(wasm.decl_file_path(resolved)),
    members,
  });

  for (const child of childNamespaces) {
    walkNamespace(
      child.original,
      child.resolved,
      child.category,
      child.path,
      child.zigPath,
      child.ancestors,
    );
  }
}

const rootResolved = resolveDeclaration(root);
walkNamespace(
  root,
  rootResolved.declaration,
  rootResolved.category,
  [],
  '@import("std")',
  new Set([rootResolved.declaration]),
);

namespaces.sort((left, right) =>
  left.path.join(".").localeCompare(right.path.join(".")),
);

process.stdout.write(
  JSON.stringify({
    module: unwrapString(wasm.module_name(0)),
    "diagnostic-count": wasmLogs.length,
    namespaces,
  }),
);

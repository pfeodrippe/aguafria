const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {test} = require("node:test");

const resources = path.join(__dirname, "../resources/learn");

// Minimal DOM for the text-node renderer, not a second implementation of it.
class Node {
  constructor(text = "", tag = "") {
    this.text = text;
    this.tag = tag;
    this.children = [];
    this.className = "";
    this.classList = {add: (...names) => { this.className += ` ${names.join(" ")}`; }};
  }
  appendChild(node) { this.children.push(node); }
  replaceChildren(...nodes) { this.text = ""; this.children = nodes; }
  get textContent() { return this.text + this.children.map(n => n.textContent).join(""); }
}

function highlight(source) {
  const code = new Node(source, "code");
  const context = vm.createContext({});
  for (const file of ["prism-core.min.js", "prism-clojure.js"]) {
    vm.runInContext(fs.readFileSync(path.join(resources, "vendor/prism", file), "utf8"), context);
  }
  context.document = {
    querySelectorAll: selector => selector.startsWith("code.language-clojure") ? [code] : [],
    createTextNode: text => new Node(text),
    createElement: tag => new Node("", tag),
    createDocumentFragment: () => new Node()
  };
  context.location = {hash: ""};
  context.window = {addEventListener() {}};
  vm.runInContext(fs.readFileSync(path.join(resources, "reference.js"), "utf8"), context);
  return code;
}

function nodes(node) { return [node, ...node.children.flatMap(nodes)]; }

test("Clojure forms, keywords, metadata, comments, literals and qualified calls have tokens", () => {
  const source = `(az/defn hello :i32
  {:attrs #{:explicit-return}}
  []
  ;; A real Clojure comment
  (let [^:var count 42]
    (testing/expect true)
    (println "hello <world>")))`;
  const code = highlight(source);
  assert.equal(code.textContent, source);
  for (const [type, text] of [["function", "az/defn"], ["keyword", "let"],
                            ["symbol", ":i32"], ["symbol", ":var"],
                            ["operator", "^"], ["number", "42"],
                            ["boolean", "true"], ["string", '"hello <world>"']]) {
    assert.ok(nodes(code).some(n => n.className.split(" ").includes(type) && n.textContent === text), `${type}: ${text}`);
  }
  assert.ok(nodes(code).some(n => n.className.includes("comment") && n.textContent.includes("real Clojure comment")));
});

test("highlighting preserves every character, including NBSP, tabs, Unicode and malformed examples", () => {
  for (const source of ["(let [x])", "\t; café ∆\r\n(println \"a\u00a0b\")\n", '#"a.*"',
                        String.raw`[\) \\ \" \newline \u03bb "normal string"]`,
                        '(println "</script><img src=x onerror=alert(1)>")']) {
    const code = highlight(source);
    assert.equal(code.textContent, source);
    assert.ok(nodes(code).every(n => ["", "code", "span"].includes(n.tag)));
  }
});

test("character quotes do not swallow later string literals", () => {
  const code = highlight(String.raw`[\" "text" \)]`);
  assert.ok(nodes(code).some(n => n.className.includes("char") && n.textContent === '\\"'));
  assert.ok(nodes(code).some(n => n.className.includes("string") && n.textContent === '"text"'));
});

test("every authored lesson keeps its exact text after highlighting", () => {
  function visit(directory) {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) visit(file);
      else if (file.endsWith(".clj")) {
        const source = fs.readFileSync(file, "utf8");
        assert.equal(highlight(source).textContent, source, file);
      }
    }
  }
  visit(path.join(resources, "examples"));
  visit(path.join(resources, "fragments"));
});

test("deep links reveal only their own language panel", () => {
  const script = fs.readFileSync(path.join(resources, "reference.js"), "utf8");
  const functionSource = script.slice(script.indexOf("function revealLinkedPanel()"));
  let clicks = 0;
  let scrolls = 0;
  let listener;
  const tab = {getAttribute: key => key === "role" ? "tab" : null, click: () => clicks++};
  const panel = {getAttribute: () => "example-at"};
  const targets = {
    "example-a": {closest: () => panel, scrollIntoView: () => scrolls++},
    "inside-code": {closest: () => panel, scrollIntoView: () => scrolls++},
    comptime: {closest: () => null},
    "example-at": tab
  };
  const context = vm.createContext({
    location: {hash: "#example-a"},
    document: {getElementById: id => targets[id]},
    window: {addEventListener: (event, fn) => { assert.equal(event, "hashchange"); listener = fn; }}
  });
  vm.runInContext(functionSource, context);
  assert.equal(clicks, 1);
  context.location.hash = "#inside-code";
  listener();
  assert.equal(clicks, 2);
  for (const hash of ["#comptime", "#missing", "#%invalid", ""]) {
    context.location.hash = hash;
    listener();
  }
  assert.equal(clicks, 2);
  assert.equal(scrolls, 2);
});

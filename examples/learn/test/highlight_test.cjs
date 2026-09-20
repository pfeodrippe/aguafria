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
    getElementById: () => null,
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
  visit(path.join(resources, "example"));
  visit(path.join(resources, "snippet"));
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

test("side-by-side is the default; explicit Zig links and per-example controls still work", () => {
  function page(hash) {
    const elements = new Map();
    function example(id) {
      const panels = ["z", "a"].map(language => {
        const panelId = `${id}-${language}`;
        const panel = {
          id: panelId,
          hidden: language === "a",
          getAttribute: () => `${panelId}t`,
          closest() { return this; },
          scrollIntoView() { this.scrolled = true; }
        };
        elements.set(panelId, panel);
        return panel;
      });
      const tabs = ["z", "a", "b"].map(language => {
        const panelId = `${id}-${language}`;
        const tabId = `${panelId}t`;
        const controls = language === "b" ? `${id}-z ${id}-a` : panelId;
        const attributes = new Map([["role", "tab"], ["aria-controls", controls]]);
        const listeners = {};
        const tab = {
          getAttribute: key => attributes.get(key),
          setAttribute: (key, value) => attributes.set(key, value),
          addEventListener: (event, handler) => { listeners[event] = handler; },
          click: () => listeners.click(),
          keydown: key => listeners.keydown({key, preventDefault() {}}),
          focus() { this.focused = true; }
        };
        elements.set(tabId, tab);
        return tab;
      });
      const classes = new Set();
      elements.set(id, classes);
      return {
        appendChild() {},
        classList: {toggle(name, active) { active ? classes.add(name) : classes.delete(name); }},
        querySelector: () => ({querySelectorAll: () => tabs}),
        querySelectorAll: selector => selector === '[role="tabpanel"]' ? panels : []
      };
    }
    const examples = [example("first"), example("second")];
    const context = vm.createContext({
      URLSearchParams,
      location: {hash},
      document: {
        createElement: tag => new Node("", tag),
        querySelectorAll: selector => selector === ".learn-example" ? examples : [],
        getElementById: id => elements.get(id)
      },
      window: {addEventListener() {}}
    });
    for (const file of ["prism-core.min.js", "prism-clojure.js"]) {
      vm.runInContext(fs.readFileSync(path.join(resources, "vendor/prism", file), "utf8"), context);
    }
    vm.runInContext(fs.readFileSync(path.join(resources, "reference.js"), "utf8"), context);
    return id => elements.get(id);
  }

  for (const hash of ["", "#comptime"]) {
    const get = page(hash);
    for (const id of ["first", "second"]) {
      assert.equal(get(`${id}-a`).hidden, false);
      assert.equal(get(`${id}-z`).hidden, false);
      assert.equal(get(`${id}-bt`).getAttribute("aria-selected"), "true");
      assert.equal(get(`${id}-at`).getAttribute("aria-selected"), "false");
      assert.equal(get(`${id}-zt`).getAttribute("aria-selected"), "false");
      assert.equal(get(`${id}-bt`).tabIndex, 0);
      assert.equal(get(`${id}-at`).tabIndex, -1);
      assert.equal(get(`${id}-zt`).tabIndex, -1);
      assert.equal(get(`${id}-at`).focused, undefined);
    }
  }
  const get = page("#second-z");
  assert.equal(get("first-a").hidden, false);
  assert.equal(get("second-z").hidden, false);
  assert.equal(get("second-a").hidden, true);
  get("first-zt").click();
  assert.equal(get("first-z").hidden, false);
  get("first-zt").keydown("ArrowRight");
  assert.equal(get("first-a").hidden, false);
  assert.equal(get("first-at").focused, true);
  assert.equal(get("second-z").hidden, false);
  get("first-bt").click();
  assert.equal(get("first-z").hidden, false);
  assert.equal(get("first-a").hidden, false);
  assert.equal(get("first-bt").getAttribute("aria-selected"), "true");
  assert.equal(get("first-at").getAttribute("aria-selected"), "false");
  assert.equal(get("first").has("learn-side-by-side"), true);
  assert.equal(get("second").has("learn-side-by-side"), false);
  get("first-bt").keydown("Home");
  assert.equal(get("first-a").hidden, true);
  assert.equal(get("first").has("learn-side-by-side"), false);
  get("first-zt").keydown("End");
  assert.equal(get("first-z").hidden, false);
  assert.equal(get("first-a").hidden, false);
  assert.equal(get("first-bt").focused, true);
  get("first-bt").keydown("ArrowRight");
  assert.equal(get("first-zt").focused, true);
  assert.equal(get("first-a").hidden, true);
});

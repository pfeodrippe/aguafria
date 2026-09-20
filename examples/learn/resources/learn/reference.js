"use strict";
// Prism supplies the Clojure lexer. Build spans with text nodes instead of HTML
// encoding so every source character (including non-breaking spaces) survives
// highlighting and Copy code exactly. No source is evaluated.
function appendClojureTokens(parent, tokens) {
  for (const token of tokens) {
    if (typeof token === "string") {
      parent.appendChild(document.createTextNode(token));
    } else {
      const span = document.createElement("span");
      span.className = `token ${token.type}`;
      if (token.alias) span.classList.add(...[].concat(token.alias));
      appendClojureTokens(span, [].concat(token.content));
      parent.appendChild(span);
    }
  }
}

// Metadata shorthand ^:var and character literals such as \) are common in
// hand-written lessons. Extend the pinned grammar without changing the vendor.
Prism.languages.clojure.symbol.pattern = /(^|[\s()\[\]{},^])::?[^\s()\[\]{}",;\\]+/;
Prism.languages.clojure.char = /\\(?:\w+|[^\s])/;
Prism.languages.clojure.string.pattern = /(^|[^\\])"(?:[^"\\]|\\[\s\S])*"/;
Prism.languages.clojure.string.lookbehind = true;

document.querySelectorAll('code.language-clojure, [data-language="aguafria"] code').forEach(code => {
  const fragment = document.createDocumentFragment();
  appendClojureTokens(fragment, Prism.tokenize(code.textContent, Prism.languages.clojure));
  code.classList.add("language-clojure");
  code.replaceChildren(fragment);
});

// Small references use an independent, keyboard-operable language toggle.
// Explanations are available as tooltips and accessible descriptions, without
// expanding every type mention into a full-size code panel inside prose.
document.querySelectorAll(".learn-inline").forEach(example => {
  const button = example;
  const zig = example.querySelector('[data-language="zig"]');
  const aguafria = example.querySelector('[data-language="aguafria"]');
  const original = zig.textContent;
  button.setAttribute("role", "button");
  button.tabIndex = 0;
  button.addEventListener("click", () => {
    const selected = button.getAttribute("aria-pressed") !== "true";
    button.setAttribute("aria-pressed", String(selected));
    button.setAttribute("aria-label", `Show ${selected ? "original Zig" : "Aguafria equivalent"} for ${original}`);
    zig.hidden = selected;
    aguafria.hidden = !selected;
  });
  button.addEventListener("keydown", event => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      button.click();
    }
  });
});

// Code and output each have their own paired height, regardless of the selected
// tab. Hidden panels are measured invisibly; all layout reads precede writes.
function matchExampleHeights() {
  const groups = Array.from(document.querySelectorAll(".learn-example"))
    .flatMap(example => [
      ["--learn-code-height", '[role="tabpanel"] > figure:first-child pre > code'],
      ["--learn-output-height", '[role="tabpanel"] > figure:not(:first-child), .learn-repl']
    ].map(([property, selector]) => ({example, property,
      blocks: Array.from(example.querySelectorAll(selector))})))
    .filter(({blocks}) => blocks.length === 2);
  const examples = new Set(groups.map(({example}) => example));
  examples.forEach(example => example.classList.add("learn-measuring"));
  const heights = groups.map(({blocks}) => Math.ceil(Math.max(
    ...blocks.map(block => block.getBoundingClientRect().height))));
  groups.forEach(({example, property}, index) => {
    example.style.setProperty(property, `${heights[index]}px`);
  });
  examples.forEach(example => example.classList.remove("learn-measuring"));
}

function refreshExampleLayout() {
  const contents = document.getElementById("contents");
  if (contents) {
    const viewport = document.documentElement.clientWidth;
    contents.style.setProperty("--learn-viewport-width", `${viewport}px`);
    // Examples can be nested in lists; position each pair against the window.
    const measurements = Array.from(document.querySelectorAll(".learn-example"), example => ({
      example,
      left: example.getBoundingClientRect().left + window.scrollX
    }));
    measurements.forEach(({example, left}) => {
      example.style.setProperty("--learn-content-left", `${left}px`);
    });
  }
  matchExampleHeights();
}

function requestedView() {
  const view = new URLSearchParams(location.search).get("view");
  const index = ["zig", "clj", "side-by-side"].indexOf(view);
  return index < 0 ? 2 : index;
}

// Original Zig remains visible without JavaScript. Query parameters choose the
// page-wide initial view; each example can still be switched independently.
document.querySelectorAll(".learn-example").forEach(example => {
  const tablist = example.querySelector('[role="tablist"]');
  const tabs = Array.from(tablist.querySelectorAll('[role="tab"]'));
  const panels = document.createElement("div");
  panels.className = "learn-panels";
  example.querySelectorAll('[role="tabpanel"]').forEach(panel => panels.appendChild(panel));
  example.appendChild(panels);
  tablist.hidden = false;
  function select(tab, focus = false) {
    const visiblePanels = tab.getAttribute("aria-controls").split(" ");
    example.classList.toggle("learn-side-by-side", visiblePanels.length === 2);
    tabs.forEach(candidate => {
      const selected = candidate === tab;
      candidate.setAttribute("aria-selected", String(selected));
      candidate.tabIndex = selected ? 0 : -1;
    });
    example.querySelectorAll('[role="tabpanel"]').forEach(panel => {
      panel.hidden = !visiblePanels.includes(panel.id);
    });
    if (focus) tab.focus();
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener("click", () => select(tab));
    tab.addEventListener("keydown", event => {
      let next;
      if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
      if (event.key === "ArrowLeft") next = (index + tabs.length - 1) % tabs.length;
      if (event.key === "Home") next = 0;
      if (event.key === "End") next = tabs.length - 1;
      if (next !== undefined) {
        event.preventDefault();
        select(tabs[next], true);
      }
    });
  });
  select(tabs[requestedView()]);
  example.querySelectorAll('[role="tabpanel"] pre code').forEach(code => {
    const button = document.createElement("button");
    button.className = "learn-copy";
    button.textContent = "Copy code";
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(code.textContent);
        button.textContent = "Copied";
      } catch (_) {
        button.textContent = "Select code to copy";
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(code);
        selection.removeAllRanges();
        selection.addRange(range);
      }
    });
    code.parentElement.before(button);
  });
});

function setupContents() {
  const navigation = document.getElementById("navigation");
  if (!navigation) return;
  navigation.classList.add("learn-navigation");
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "learn-toc-toggle";
  toggle.textContent = "☰";
  toggle.setAttribute("aria-label", "Table of contents");
  toggle.setAttribute("aria-controls", "navigation");
  document.body.appendChild(toggle);
  function setOpen(open, restoreFocus = false) {
    navigation.hidden = !open;
    toggle.setAttribute("aria-expanded", String(open));
    if (open) {
      const entry = navigation.querySelector('[aria-current="location"]') || navigation.querySelector('a');
      entry?.focus({preventScroll: true});
    }
    if (restoreFocus) toggle.focus();
  }
  setOpen(false);
  toggle.addEventListener("click", () => setOpen(navigation.hidden));
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && !navigation.hidden) {
      event.preventDefault();
      setOpen(false, true);
    }
  });
  document.addEventListener("pointerdown", event => {
    if (!navigation.hidden && !navigation.contains(event.target) && !toggle.contains(event.target)) {
      setOpen(false);
    }
  });

  function revealCurrentSection() {
    navigation.querySelectorAll('a[aria-current]').forEach(link => link.removeAttribute("aria-current"));
    const link = Array.from(navigation.querySelectorAll('a[href^="#"]'))
      .find(link => link.hash === location.hash);
    if (!link) return;
    link.setAttribute("aria-current", "location");
  }
  navigation.addEventListener("click", event => {
    const link = event.target.closest('a[href^="#"]');
    if (link) setOpen(false);
  });
  window.addEventListener("hashchange", revealCurrentSection);
  revealCurrentSection();
}

setupContents();
refreshExampleLayout();
window.addEventListener("resize", refreshExampleLayout);
if (document.fonts) document.fonts.ready.then(refreshExampleLayout);

// Explicit links select their language, overriding the Aguafria default only for
// that example. Ordinary upstream heading anchors remain untouched.
function revealLinkedPanel() {
  let id;
  try { id = decodeURIComponent(location.hash.slice(1)); }
  catch (_) { return; }
  if (!id) return;
  const target = document.getElementById(id);
  const panel = target?.closest('[role="tabpanel"]');
  if (!panel) return;
  const tab = document.getElementById(panel.getAttribute("aria-labelledby"));
  if (tab?.getAttribute("role") === "tab") {
    tab.click();
    target.scrollIntoView({block: "start"});
  }
}
window.addEventListener("hashchange", revealLinkedPanel);
revealLinkedPanel();

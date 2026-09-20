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
  const button = example.querySelector(".learn-inline-toggle");
  const zig = example.querySelector('[data-language="zig"]');
  const aguafria = example.querySelector('[data-language="aguafria"]');
  const original = zig.textContent;
  button.hidden = false;
  button.addEventListener("click", () => {
    const selected = button.getAttribute("aria-pressed") !== "true";
    button.setAttribute("aria-pressed", String(selected));
    button.setAttribute("aria-label", `Show ${selected ? "original Zig" : "Aguafria equivalent"} for ${original}`);
    zig.hidden = selected;
    aguafria.hidden = !selected;
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

let comparisonLayout = null;

function positionComparison(example) {
  if (!comparisonLayout) return;
  const panels = example.querySelector(".learn-panels");
  const gap = parseFloat(getComputedStyle(panels).columnGap);
  panels.scrollLeft = Math.max(0, example.clientWidth + gap - comparisonLayout.start);
}

function refreshExampleLayout() {
  const contents = document.getElementById("contents");
  if (contents) {
    const viewport = document.documentElement.clientWidth;
    comparisonLayout = {start: viewport / 2};
    contents.style.setProperty("--learn-viewport-width", `${viewport}px`);
    contents.style.setProperty("--learn-comparison-start", `${comparisonLayout.start}px`);
    // Examples can be nested in lists. Preserve their individual width while
    // positioning each comparison against the window, not its list indent.
    const measurements = Array.from(document.querySelectorAll(".learn-example"), example => ({
      example, width: example.clientWidth,
      left: example.getBoundingClientRect().left + window.scrollX
    }));
    measurements.forEach(({example, width, left}) => {
      example.style.setProperty("--learn-column-width", `${width}px`);
      example.style.setProperty("--learn-content-left", `${left}px`);
    });
    document.querySelectorAll(".learn-side-by-side").forEach(positionComparison);
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
    if (visiblePanels.length === 2) positionComparison(example);
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
  toggle.textContent = "☰ Contents";
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

  const branches = new Map();
  navigation.querySelectorAll('nav[aria-labelledby="table-of-contents"] li').forEach((item, index) => {
    const children = item.querySelector(":scope > ul");
    const link = item.querySelector(":scope > a");
    if (!children || !link) return;
    children.id = `learn-toc-children-${index}`;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "learn-toc-branch";
    button.setAttribute("aria-label", `Toggle ${link.textContent} sections`);
    button.setAttribute("aria-controls", children.id);
    function expand(open) {
      children.hidden = !open;
      button.setAttribute("aria-expanded", String(open));
      button.textContent = open ? "▾" : "▸";
    }
    branches.set(children, expand);
    expand(false);
    button.addEventListener("click", () => expand(children.hidden));
    link.before(button);
  });
  function revealCurrentSection() {
    navigation.querySelectorAll('a[aria-current]').forEach(link => link.removeAttribute("aria-current"));
    const link = Array.from(navigation.querySelectorAll('a[href^="#"]'))
      .find(link => link.hash === location.hash);
    if (!link) return;
    link.setAttribute("aria-current", "location");
    for (let parent = link.parentElement; parent !== navigation; parent = parent.parentElement) {
      branches.get(parent)?.(true);
    }
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

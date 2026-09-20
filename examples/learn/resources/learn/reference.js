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

// Original Zig remains visible without JavaScript. Interactive examples default
// to Aguafria; explicit language links are applied after initialization.
document.querySelectorAll(".learn-example").forEach(example => {
  const tablist = example.querySelector('[role="tablist"]');
  const tabs = Array.from(tablist.querySelectorAll('[role="tab"]'));
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
  select(tabs[1]);
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

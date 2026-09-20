const assert = require("node:assert/strict");
const path = require("node:path");
const {pathToFileURL} = require("node:url");
const {test} = require("node:test");
const {chromium} = require("playwright");

const reference = pathToFileURL(path.join(__dirname, "../build/site/index.html")).href;
const near = (actual, expected, label) =>
  assert.ok(Math.abs(actual - expected) < 1, `${label}: ${actual} != ${expected}`);

async function geometry(page, file = "hello_again.zig") {
  return page.evaluate(file => {
    const example = document.querySelector(`.learn-example[aria-label="${file}"]`);
    const box = selector => {
      const element = example.querySelector(selector);
      if (!element || !element.getClientRects().length) return null;
      const {x, y, width, height} = element.getBoundingClientRect();
      return {x, y, width, height};
    };
    return {
      tabs: box('.learn-tabs'),
      panels: box('.learn-panels'),
      gap: parseFloat(getComputedStyle(example.querySelector('.learn-panels')).columnGap),
      zig: box('[id$="-z"] > figure:first-child pre > code'),
      clojure: box('[id$="-a"] > figure:first-child pre > code'),
      shell: box('[id$="-z"] > figure:not(:first-child)'),
      repl: box('.learn-repl'),
      shellText: box('[id$="-z"] > figure:not(:first-child) pre > samp'),
      replText: box('.learn-repl pre > samp'),
      navOpen: !document.getElementById("navigation").hidden,
      scrollX: window.scrollX,
      comparisonScroll: example.querySelector('.learn-panels').scrollLeft,
      scrollWidth: document.documentElement.scrollWidth,
      width: document.documentElement.clientWidth
    };
  }, file);
}

test("fixed tabs and equal-width pairs fit the viewport without scrolling the container", async () => {
  const browser = await chromium.launch({channel: process.env.LEARN_BROWSER || "chrome"});
  try {
    for (const width of [2600, 1600, 1194, 900, 640]) {
      const page = await browser.newPage({viewport: {width, height: 1000}});
      const errors = [];
      page.on("pageerror", error => errors.push(error.message));
      await page.goto(`${reference}?view=clj#Hello-World`);
      const example = page.getByRole("region", {name: "hello_again.zig", exact: true});
      const other = page.getByRole("region", {name: "hello.zig", exact: true});
      const initial = await geometry(page);
      await example.getByRole("tab", {name: "Zig", exact: true}).click();
      const zigOnly = await geometry(page);
      near(zigOnly.zig.height, initial.clojure.height, "individual code height");
      near(zigOnly.shell.height, initial.repl.height, "individual output height");
      await example.getByRole("tab", {name: "Side by side", exact: true}).click();
      const paired = await geometry(page);
      near(paired.clojure.x, (width + paired.gap) / 2, `${width}: centered pair`);
      near(paired.tabs.x, initial.tabs.x, "tabs stay in their original position");
      near(paired.tabs.width, initial.tabs.width, "tabs retain their width");
      near(paired.clojure.width, paired.zig.width, `${width}: equal columns`);
      near(paired.panels.width, width, `${width}: container fits viewport`);
      assert.ok(paired.zig.x >= 0);
      assert.ok(paired.clojure.x + paired.clojure.width <= width);
      near(paired.zig.y, paired.clojure.y, "source top alignment");
      near(paired.zig.height, paired.clojure.height, "paired code height");
      near(paired.shell.y, paired.repl.y, "output top alignment");
      near(paired.shell.height, paired.repl.height, "paired output height");
      near(paired.shellText.y, paired.replText.y, "output text top alignment");
      near(paired.shellText.height, paired.replText.height, "output text height");
      assert.ok(paired.zig.x + paired.zig.width < paired.clojure.x);
      assert.equal(paired.navOpen, false);
      await example.evaluate(element => {
        const panels = element.querySelector('.learn-panels');
        panels.scrollLeft = panels.scrollWidth;
      });
      const rightEdge = await geometry(page);
      assert.equal(rightEdge.comparisonScroll, 0, "both columns fit without container scrolling");
      assert.ok(rightEdge.clojure.x + rightEdge.clojure.width <= width + 1,
        "the full right column is reachable");
      assert.equal(rightEdge.scrollX, 0, "comparisons do not scroll the whole document");
      near(rightEdge.tabs.x, initial.tabs.x, "scrolling does not move tabs");
      await other.getByRole("tab", {name: "Side by side", exact: true}).click();
      await example.getByRole("tab", {name: "Aguafria Zig", exact: true}).click();
      assert.equal((await geometry(page)).navOpen, false);
      await other.getByRole("tab", {name: "Aguafria Zig", exact: true}).click();
      const restored = await geometry(page);
      assert.equal(restored.navOpen, false, "contents visibility is independent of view selection");
      near(restored.clojure.width, initial.clojure.width, "restored width");
      near(restored.clojure.x, initial.clojure.x, "restored position");
      assert.deepEqual(errors, []);
      console.log(`Verified ${width}px layout`);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test("every source and output pair stays matched across live resizing", async () => {
  const browser = await chromium.launch({channel: process.env.LEARN_BROWSER || "chrome"});
  try {
    const page = await browser.newPage({viewport: {width: 2600, height: 1000}});
    await page.goto(reference);
    await page.evaluate(() => {
      document.querySelectorAll('.learn-tabs [id$="-bt"]').forEach(tab => tab.click());
    });
    for (const width of [2600, 1194, 640, 1600, 2600]) {
      await page.setViewportSize({width, height: 1000});
      // Let the browser deliver resize/font events and complete layout.
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const pairs = await page.evaluate(() => Array.from(document.querySelectorAll('.learn-example')).map(example => {
        const heights = selector => Array.from(example.querySelectorAll(selector), block => block.getBoundingClientRect().height);
        const code = heights('[role="tabpanel"] > figure:first-child pre > code');
        const output = heights('[role="tabpanel"] > figure:not(:first-child) pre > samp, .learn-repl pre > samp');
        // Removing the matched minimum recovers each pair's natural heights.
        example.classList.add('learn-measuring');
        const naturalCode = heights('[role="tabpanel"] > figure:first-child pre > code');
        const naturalOutput = heights('[role="tabpanel"] > figure:not(:first-child), .learn-repl');
        example.classList.remove('learn-measuring');
        const clojure = example.querySelector('[id$="-a"] > figure:first-child pre > code');
        return {file: example.getAttribute('aria-label'), code, output, naturalCode, naturalOutput,
          clojureLeft: clojure?.getBoundingClientRect().x,
          midpoint: (document.documentElement.clientWidth +
            parseFloat(getComputedStyle(example.querySelector('.learn-panels')).columnGap)) / 2,
          fits: Array.from(example.querySelectorAll('[role="tabpanel"]')).every(panel => {
            const box = panel.getBoundingClientRect();
            return box.x >= -1 && box.right <= document.documentElement.clientWidth + 1;
          }),
          unwrapped: Array.from(example.querySelectorAll('pre code, pre samp')).every(block =>
            getComputedStyle(block).whiteSpace === 'pre'),
          outputMinimum: parseFloat(example.style.getPropertyValue('--learn-output-height'))};
      }));
      assert.equal(pairs.length, 307);
      for (const {file, code, output, naturalCode, naturalOutput, outputMinimum, clojureLeft, midpoint, fits, unwrapped} of pairs) {
        assert.ok(fits, `${width}: ${file} columns stay inside viewport`);
        assert.ok(unwrapped, `${width}: ${file} code and output never wrap`);
        if (code.length === 2) {
          near(clojureLeft, midpoint, `${width}: ${file} midpoint after resizing`);
          near(code[0], code[1], `${width}: ${file} code`);
          near(code[0], Math.max(...naturalCode), `${width}: ${file} taller code`);
        }
        if (output.length === 2) {
          near(output[0], output[1], `${width}: ${file} output`);
          near(outputMinimum, Math.max(...naturalOutput), `${width}: ${file} taller output`);
        }
      }
    }
  } finally {
    await browser.close();
  }
});

test("URL view selection defaults to side-by-side and preserves independent tabs", async () => {
  const browser = await chromium.launch({channel: process.env.LEARN_BROWSER || "chrome"});
  try {
    const page = await browser.newPage({viewport: {width: 1600, height: 1000}});
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    for (const [query, label] of [
      ["", "Side by side"],
      ["?view=zig", "Zig"],
      ["?view=clj", "Aguafria Zig"],
      ["?view=side-by-side", "Side by side"],
      ["?view=invalid", "Side by side"]
    ]) {
      await page.goto(`${reference}${query}#Hello-World`);
      assert.equal(await page.getByRole('tab', {name: label, exact: true, selected: true}).count(), 307);
    }
    await page.goto(`${reference}?view=side-by-side#Hello-World`);
    const example = page.getByRole('region', {name: 'hello_again.zig', exact: true});
    await example.getByRole('tab', {name: 'Aguafria Zig', exact: true}).click();
    assert.equal(await page.getByRole('tab', {name: 'Side by side', exact: true, selected: true}).count(), 306);
    await example.getByRole('tab', {name: 'Aguafria Zig', exact: true}).press('End');
    assert.equal(await page.getByRole('tab', {name: 'Side by side', exact: true, selected: true}).count(), 307);
    const zigPanel = await example.getByRole('tab', {name: 'Zig', exact: true}).getAttribute('aria-controls');
    await page.goto(`${reference}?view=side-by-side#${zigPanel}`);
    assert.equal(await example.getByRole('tab', {name: 'Zig', exact: true}).getAttribute('aria-selected'), 'true');
    assert.equal(await page.getByRole('tab', {name: 'Side by side', exact: true, selected: true}).count(), 306);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});

test("contents drawer keeps the complete upstream tree expanded without moving examples", async () => {
  const browser = await chromium.launch({channel: process.env.LEARN_BROWSER || "chrome"});
  try {
    for (const width of [1600, 640]) {
      const page = await browser.newPage({viewport: {width, height: 1000}});
      await page.goto(`${reference}?view=side-by-side#Hello-World`);
      const initial = await geometry(page);
      const toggle = page.getByRole('button', {name: 'Table of contents', exact: true});
      assert.equal(await toggle.textContent(), '☰');
      const navigation = page.locator('#navigation');
      assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
      await toggle.click();
      assert.equal(await navigation.isVisible(), true);
      near((await geometry(page)).tabs.x, initial.tabs.x, 'drawer does not shift the tabs');
      near((await geometry(page)).clojure.x, initial.clojure.x, 'drawer does not shift the panels');
      assert.equal(await navigation.locator('ul[hidden]').count(), 0);
      assert.equal(await navigation.locator('.learn-toc-branch').count(), 0);
      assert.equal(await navigation.getByRole('link', {name: 'Primitive Types', exact: true}).isVisible(), true);
      assert.equal(await page.locator('.learn-inline-toggle').count(), 0);
      assert.equal(await page.locator('.learn-inline').first().textContent().then(text => text.includes('⇄')), false);
      await navigation.getByRole('link', {name: 'Primitive Types', exact: true}).click();
      assert.equal(new URL(page.url()).hash, '#Primitive-Types');
      assert.equal(new URL(page.url()).searchParams.get('view'), 'side-by-side');
      assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
      await toggle.click();
      assert.equal(await navigation.locator('ul[hidden]').count(), 0);
      assert.equal(await navigation.getByRole('link', {name: 'Primitive Types', exact: true}).getAttribute('aria-current'), 'location');
      await page.keyboard.press('Escape');
      assert.equal(await navigation.isVisible(), false);
      assert.equal(await toggle.evaluate(element => document.activeElement === element), true);
      await toggle.click();
      await page.locator('#contents').click({position: {x: width > 1024 ? 450 : 500, y: 10}});
      assert.equal(await navigation.isVisible(), false);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

const assert = require("node:assert/strict");
const path = require("node:path");
const {pathToFileURL} = require("node:url");
const {test} = require("node:test");
const {chromium} = require("playwright");

const reference = pathToFileURL(path.join(__dirname, "../build/site/index.html")).href;
const tooltipText = text => text.replace(/^```[^\n]*\n?/gm, "").trim();

async function withPage(action) {
  const browser = await chromium.launch({channel: process.env.LEARN_BROWSER || "chrome"});
  try {
    const page = await browser.newPage({viewport: {width: 1600, height: 1000}});
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto(reference);
    await action(page);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
}

test("every annotated lesson has valid spans and no native browser type tooltip", async () => {
  await withPage(async page => {
    const result = await page.evaluate(() => {
      const codes = Array.from(document.querySelectorAll("code[data-type-report]"));
      const failures = [];
      for (const code of codes) {
        const label = code.closest(".learn-example")?.getAttribute("aria-label");
        const source = code.textContent;
        const reports = JSON.parse(code.dataset.typeReport);
        if (!reports.length) failures.push({label, reason: "empty report"});
        for (const [start, end, information] of reports) {
          if (!(Number.isInteger(start) && Number.isInteger(end) &&
                start >= 0 && start < end && end <= source.length &&
                typeof information === "string" && information.length)) {
            failures.push({label, reason: "invalid source span", start, end});
          }
        }
        if (!code.querySelector(".learn-typed")) {
          failures.push({label, reason: "no hover targets"});
        }
        for (const span of code.querySelectorAll(".learn-typed")) {
          if (span.closest("[title]")) failures.push({label, reason: "duplicate native tooltip"});
        }
      }
      return {annotated: codes.length, failures};
    });
    assert.ok(result.annotated >= 291);
    assert.deepEqual(result.failures, []);
    assert.equal(await page.getByRole("tooltip", {includeHidden: true}).count(), 1);
  });
});

test("local binding hover displays its Zig-tool result and clears on Escape or scroll", async () => {
  await withPage(async page => {
    const example = page.getByRole("region", {name: "test_single_item_pointer.zig", exact: true});
    const code = example.locator(".learn-aguafria code");
    const x = code.locator(".learn-typed").filter({hasText: /^x$/}).first();
    const information = await x.getAttribute("data-type-info");
    assert.match(information, /^ZLS · generated Zig/);
    assert.match(information, /i32/);
    await x.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await x.hover();
    const tooltip = page.locator("#learn-type-tooltip");
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(await tooltip.textContent(), tooltipText(information));
    assert.doesNotMatch(await tooltip.textContent(), /```/);
    const box = await tooltip.boundingBox();
    assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= 1600 && box.y + box.height <= 1000);
    await page.keyboard.press("Escape");
    assert.equal(await tooltip.isVisible(), false);
    await page.mouse.move(0, 0);
    await x.hover();
    assert.equal(await tooltip.isVisible(), true);
    await code.evaluate(element => element.dispatchEvent(new Event("scroll")));
    assert.equal(await tooltip.isVisible(), false);

    const unresolved = code.locator(".learn-typed").filter({hasText: /^ns$/}).first();
    await unresolved.hover();
    assert.match(await tooltip.textContent(), /^Type unresolved/);
    assert.doesNotMatch(await tooltip.textContent(), /Compiler-confirmed|ZLS · generated Zig/);
  });
});

test("type information is keyboard accessible and does not alter copied source", async () => {
  await withPage(async page => {
    const example = page.getByRole("region", {name: "test_single_item_pointer.zig", exact: true});
    const code = example.locator(".learn-aguafria code");
    const original = await code.textContent();
    const reports = JSON.parse(await code.getAttribute("data-type-report"));
    await code.focus();
    await page.keyboard.press("Alt+ArrowDown");
    const tooltip = page.locator("#learn-type-tooltip");
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(await tooltip.textContent(), tooltipText(reports[0][2]));
    await page.keyboard.press("Alt+ArrowDown");
    assert.equal(await tooltip.textContent(), tooltipText(reports[1][2]));
    await page.keyboard.press("Alt+ArrowUp");
    assert.equal(await tooltip.textContent(), tooltipText(reports[0][2]));
    assert.equal(await code.textContent(), original);
    await code.evaluate(element => element.blur());
    assert.equal(await tooltip.isVisible(), false);
  });
});

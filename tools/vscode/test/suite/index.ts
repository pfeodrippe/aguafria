import * as assert from "node:assert";
import * as path from "node:path";
import * as vscode from "vscode";

type AguafriaApi = {
  startDevelopmentProgram: () => Promise<void>;
  evaluate: (mode: "declaration" | "selection" | "changed" | "file") => Promise<Record<string, unknown>>;
  invokeFunction: (
    document: vscode.TextDocument,
    functionName: string,
    argumentsValue: unknown[],
  ) => Promise<unknown>;
  programStatus: () => Promise<Record<string, unknown>>;
  connect: () => Promise<void>;
  connected: () => boolean;
  runtimeIdentity: () => RuntimeIdentity | undefined;
};

type RuntimeIdentity = {
  runtimeId: string;
  projectId: string;
  jvmPid: number;
  workspaceRoot: string;
};

async function fixtureEditor(): Promise<{
  fixture: vscode.Uri;
  document: vscode.TextDocument;
  editor: vscode.TextEditor;
  answerOffset: number;
}> {
  const workspace = vscode.workspace.workspaceFolders?.[0];
  assert.ok(workspace, "integration test has a workspace");
  const fixture = vscode.Uri.file(path.join(workspace.uri.fsPath, "main.zig"));
  const document = await vscode.workspace.openTextDocument(fixture);
  const editor = await vscode.window.showTextDocument(document);
  const answerOffset = document.getText().indexOf("42");
  assert.ok(answerOffset >= 0);
  return { fixture, document, editor, answerOffset };
}

async function replaceAnswer(
  document: vscode.TextDocument,
  editor: vscode.TextEditor,
  answerOffset: number,
  replacement: string,
): Promise<vscode.Position> {
  const start = document.positionAt(answerOffset);
  const changed = await editor.edit((builder) => {
    builder.replace(new vscode.Range(start, start.translate(0, 2)), replacement);
  });
  assert.strictEqual(changed, true);
  editor.selection = new vscode.Selection(start, start);
  return start;
}

async function waitForProgramFinish(api: AguafriaApi): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (String((await api.programStatus()).status).replace(/^:/, "") === "finished") return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.fail("native fixture did not finish after publication");
}

export async function run(): Promise<void> {
  const extension = vscode.extensions.getExtension<AguafriaApi>("aguafria.aguafria-zig");
  assert.ok(extension, "Aguafria extension is installed in the development host");
  const api = await extension.activate();
  const phase = process.env.AGUAFRIA_VSCODE_TEST_PHASE ?? "single";
  const { fixture, document, editor, answerOffset } = await fixtureEditor();

  if (phase === "reload-connect") {
    await api.connect();
    assert.strictEqual(api.connected(), true);
    assert.ok(api.runtimeIdentity());
    assert.deepStrictEqual(await api.invokeFunction(document, "caller", []), { value: 43 });

    await replaceAnswer(document, editor, answerOffset, "44");
    const publication = await api.evaluate("declaration");
    assert.strictEqual(String(publication.status).replace(/^:/, ""), "published");
    assert.deepStrictEqual(await api.invokeFunction(document, "caller", []), { value: 44 });
    await waitForProgramFinish(api);
    await vscode.commands.executeCommand("aguafria.stopDevelopmentProgram");
    return;
  }

  await api.startDevelopmentProgram();
  assert.strictEqual(api.connected(), true);
  assert.strictEqual(String((await api.programStatus()).status).replace(/^:/, ""), "running");
  const initial = await api.evaluate("file");
  assert.strictEqual(String(initial.status).replace(/^:/, ""), "published");
  assert.deepStrictEqual(await api.invokeFunction(document, "caller", []), { value: 42 });

  await vscode.commands.executeCommand("aguafria.disconnect");
  assert.strictEqual(api.connected(), false);
  await vscode.commands.executeCommand("aguafria.connect");
  assert.strictEqual(api.connected(), true);
  assert.strictEqual(String((await api.programStatus()).status).replace(/^:/, ""), "running");
  assert.deepStrictEqual(await api.invokeFunction(document, "caller", []), { value: 42 });

  const start = document.positionAt(answerOffset);
  await editor.edit((builder) => builder.replace(new vscode.Range(start, start.translate(0, 2)), "{"));
  editor.selection = new vscode.Selection(start, start);
  await assert.rejects(() => api.evaluate("declaration"));
  assert.deepStrictEqual(await api.invokeFunction(document, "caller", []), { value: 42 });
  assert.ok(vscode.languages.getDiagnostics(fixture).length > 0);
  assert.strictEqual(String((await api.programStatus()).status).replace(/^:/, ""), "running");

  await editor.edit((builder) => builder.replace(new vscode.Range(start, start.translate(0, 1)), "43"));
  const changed = await api.evaluate("declaration");
  assert.strictEqual(String(changed.status).replace(/^:/, ""), "published");
  assert.deepStrictEqual(await api.invokeFunction(document, "caller", []), { value: 43 });
  assert.strictEqual(vscode.languages.getDiagnostics(fixture).length, 0);

  if (phase === "reload-start") {
    assert.ok(api.runtimeIdentity());
    await vscode.commands.executeCommand("workbench.action.files.revert");
    return;
  }

  await waitForProgramFinish(api);

  await vscode.commands.executeCommand("aguafria.stopDevelopmentProgram");
}

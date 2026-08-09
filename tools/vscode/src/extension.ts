import * as vscode from "vscode";
import * as path from "node:path";
import { spawn, type ChildProcess } from "node:child_process";
import { createHash } from "node:crypto";
import { NReplClient, resultFrom, type BValue } from "./protocol";
import { readDiscovery, type Handshake } from "./discovery";

type ObjectValue = { [key: string]: BValue };

type RuntimeIdentity = {
  runtimeId: string;
  projectId: string;
  jvmPid: number;
  workspaceRoot: string;
};

let context: vscode.ExtensionContext;
let client: NReplClient | undefined;
let token: string | undefined;
let handshake: Handshake | undefined;
let ownedServer: ChildProcess | undefined;
let output: vscode.OutputChannel;
let diagnostics: vscode.DiagnosticCollection;
let statusBar: vscode.StatusBarItem;
let monitor: HotReloadTree;
let latestTicketId: string | undefined;
const acceptedSourceHashes = new Map<string, string>();

function identityFrom(discovered: Handshake): RuntimeIdentity {
  return {
    runtimeId: discovered.runtimeId,
    projectId: discovered.projectId,
    jvmPid: discovered.jvmPid,
    workspaceRoot: discovered.workspaceRoot,
  };
}

async function waitForProcessExit(pid: number, timeoutMs = 5_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0);
    } catch {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  return false;
}

function asObject(value: BValue): ObjectValue {
  if (typeof value !== "object" || Array.isArray(value) || value === null) {
    throw new Error("Aguafria returned an invalid object response");
  }
  return value as ObjectValue;
}

function statusName(value: BValue | undefined): string {
  return typeof value === "string" ? value.replace(/^:/, "") : String(value ?? "unknown");
}

function activeWorkspace(): vscode.WorkspaceFolder {
  const editor = vscode.window.activeTextEditor;
  const folder = editor ? vscode.workspace.getWorkspaceFolder(editor.document.uri) : undefined;
  const selected = folder ?? vscode.workspace.workspaceFolders?.[0];
  if (!selected) throw new Error("Open an Aguafria/Zig workspace first");
  return selected;
}

async function verifyConnection(discovered: Handshake, discoveredToken: string): Promise<void> {
  const next = new NReplClient();
  try {
    await next.connect("127.0.0.1", discovered.port);
    const described = asObject(resultFrom(await next.request({ op: "aguafria/describe" }, 5_000)));
    if (Number(described["protocol-version"]) !== discovered.protocolVersion) {
      throw new Error("The discovered nREPL uses a different Aguafria protocol version");
    }
    if (described["runtime-id"] !== discovered.runtimeId) {
      throw new Error("The discovered nREPL belongs to a different Aguafria runtime");
    }
    const projectIds = Array.isArray(described["project-ids"])
      ? described["project-ids"].map(String)
      : [];
    if (!projectIds.includes(discovered.projectId)) {
      throw new Error("The discovered nREPL does not own the expected Aguafria project");
    }
    const capabilities = new Set(
      Array.isArray(described.capabilities) ? described.capabilities.map((value) => statusName(value)) : [],
    );
    for (const required of [
      "eval-declaration",
      "async-publication",
      "structured-diagnostics",
      "lifecycle-shutdown",
    ] as const) {
      if (!capabilities.has(required)) {
        throw new Error(`The discovered Aguafria runtime lacks required capability ${required}`);
      }
    }
    const status = asObject(resultFrom(await next.request({
      op: "aguafria/status",
      token: discoveredToken,
      "runtime-id": discovered.runtimeId,
    }, 5_000)));
    acceptedSourceHashes.clear();
    const projects = typeof status.projects === "object" && status.projects !== null
      && !Array.isArray(status.projects) ? status.projects as ObjectValue : {};
    const project = typeof projects[discovered.projectId] === "object"
      && projects[discovered.projectId] !== null
      && !Array.isArray(projects[discovered.projectId])
      ? projects[discovered.projectId] as ObjectValue
      : {};
    const documents = typeof project.documents === "object" && project.documents !== null
      && !Array.isArray(project.documents) ? project.documents as ObjectValue : {};
    for (const [uri, document] of Object.entries(documents)) {
      if (typeof document === "object" && document !== null && !Array.isArray(document)) {
        const hash = (document as ObjectValue)["source-hash"];
        if (typeof hash === "string") acceptedSourceHashes.set(uri, hash);
      }
    }
  } catch (error) {
    next.close();
    throw error;
  }
  client?.close();
  client = next;
  token = discoveredToken;
  handshake = discovered;
  statusBar.text = "$(debug-alt) Aguafria ready";
  statusBar.tooltip = `Runtime ${discovered.runtimeId}\nProject ${discovered.projectId}`;
  statusBar.show();
  await vscode.commands.executeCommand("setContext", "aguafria.connected", true);
}

async function connect(): Promise<void> {
  if (!vscode.workspace.isTrusted) {
    throw new Error("Trust this workspace before connecting to an Aguafria native runtime");
  }
  const folder = activeWorkspace();
  const allowExternal = vscode.workspace.getConfiguration("aguafria", folder.uri)
    .get<boolean>("allowExternalProjectRoot", false);
  const discovered = await readDiscovery(folder.uri.fsPath, allowExternal);
  await verifyConnection(discovered.handshake, discovered.token);
  output.appendLine(`Connected to Aguafria runtime ${discovered.handshake.runtimeId}`);
}

function serverSdeps(): string {
  const serverRoot = path.join(context.extensionPath, "dist", "server");
  const source = JSON.stringify(path.join(serverRoot, "src"));
  const resources = JSON.stringify(path.join(serverRoot, "resources"));
  return `{:paths [${source} ${resources}] :deps {org.clojure/clojure {:mvn/version "1.12.0"} nrepl/nrepl {:mvn/version "1.3.1"}}}`;
}

async function waitForDiscovery(folder: vscode.WorkspaceFolder, timeoutMs: number): Promise<void> {
  const started = Date.now();
  let latestError: unknown;
  while (Date.now() - started < timeoutMs) {
    if (ownedServer?.exitCode !== null && ownedServer?.exitCode !== undefined) {
      throw new Error(`Aguafria editor server exited with ${ownedServer.exitCode}`);
    }
    try {
      const allowExternal = vscode.workspace.getConfiguration("aguafria", folder.uri)
        .get<boolean>("allowExternalProjectRoot", false);
      const discovered = await readDiscovery(folder.uri.fsPath, allowExternal);
      await verifyConnection(discovered.handshake, discovered.token);
      return;
    } catch (error) {
      latestError = error;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  throw new Error(`Timed out starting Aguafria: ${String(latestError)}`);
}

async function startDevelopmentProgram(): Promise<void> {
  if (!vscode.workspace.isTrusted) {
    throw new Error("Trust this workspace before running native Zig code");
  }
  const folder = activeWorkspace();
  let reused = false;
  try {
    await connect();
    reused = true;
  } catch (error) {
    output.appendLine(`No reusable Aguafria runtime: ${String(error)}`);
  }
  const configuration = vscode.workspace.getConfiguration("aguafria", folder.uri);
  const timeoutMs = configuration.get<number>("serverStartupTimeoutMs", 300_000);
  if (!reused) {
    const executable = configuration.get<string>("clojureExecutable", "clojure");
    const maximumHeap = configuration.get<string>("jvmMaxHeap", "8g").trim();
    const args = [
      "-J--enable-native-access=ALL-UNNAMED",
      ...(maximumHeap ? [`-J-Xmx${maximumHeap}`] : []),
      "-Sdeps",
      serverSdeps(),
      "-M",
      "-m",
      "aguafria.zig.editor-server",
      folder.uri.fsPath,
    ];
    statusBar.text = "$(loading~spin) Aguafria starting";
    statusBar.show();
    output.appendLine(`Starting Aguafria for ${folder.uri.fsPath}`);
    ownedServer = spawn(executable, args, {
      cwd: folder.uri.fsPath,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    ownedServer.stdout?.on("data", (data) => output.append(data.toString()));
    ownedServer.stderr?.on("data", (data) => output.append(data.toString()));
    await waitForDiscovery(folder, timeoutMs);
    if (!handshake) throw new Error("Aguafria started without publishing its runtime identity");
  }
  if (!client || token === undefined || !handshake) {
    throw new Error("Aguafria connected without a runtime identity");
  }
  statusBar.text = "$(loading~spin) Aguafria indexing Zig project";
  const bootstrap = asObject(resultFrom(await client.request({
    op: "aguafria/bootstrap-project",
    token,
    "project-id": handshake.projectId,
    "runtime-id": handshake.runtimeId,
  }, timeoutMs)));
  output.appendLine(`Project bootstrap: ${JSON.stringify(bootstrap)}`);
  const program = asObject(resultFrom(await client.request({
    op: "aguafria/start-program",
    token,
    "project-id": handshake.projectId,
    "runtime-id": handshake.runtimeId,
  }, timeoutMs)));
  output.appendLine(`Native program: ${JSON.stringify(program)}`);
  statusBar.text = statusName(program.status) === "running"
    ? "$(debug-start) Aguafria program running"
    : "$(debug-alt) Aguafria runtime ready";
  vscode.window.showInformationMessage(reused
    ? "Reconnected to the existing Aguafria development program"
    : "Aguafria development program started");
}

async function stopDevelopmentProgram(): Promise<void> {
  const connectedRuntime = handshake;
  let shutdownAccepted = false;
  if (client?.connected && token !== undefined && connectedRuntime) {
    try {
      const result = asObject(resultFrom(await client.request({
        op: "aguafria/shutdown",
        token,
        "runtime-id": connectedRuntime.runtimeId,
      }, 5_000)));
      shutdownAccepted = statusName(result.status) === "stopping";
    } catch (error) {
      output.appendLine(`Graceful runtime shutdown failed: ${String(error)}`);
    }
  }
  client?.close();
  client = undefined;
  token = undefined;
  handshake = undefined;
  acceptedSourceHashes.clear();
  if (shutdownAccepted && connectedRuntime) {
    const exited = await waitForProcessExit(connectedRuntime.jvmPid);
    output.appendLine(exited
      ? `Stopped authenticated Aguafria JVM pid ${connectedRuntime.jvmPid}`
      : `Aguafria JVM pid ${connectedRuntime.jvmPid} accepted shutdown; exit is still pending`);
  } else if (ownedServer && ownedServer.exitCode === null) {
    ownedServer.kill("SIGTERM");
    output.appendLine(`Stopped extension-owned Aguafria JVM pid ${ownedServer.pid}`);
  } else {
    output.appendLine("Disconnected; no authenticated Aguafria runtime accepted shutdown");
  }
  ownedServer = undefined;
  diagnostics.clear();
  statusBar.text = "$(debug-disconnect) Aguafria disconnected";
  await vscode.commands.executeCommand("setContext", "aguafria.connected", false);
}

async function disconnectEditor(): Promise<void> {
  client?.close();
  client = undefined;
  token = undefined;
  handshake = undefined;
  acceptedSourceHashes.clear();
  diagnostics.clear();
  statusBar.text = "$(debug-disconnect) Aguafria disconnected";
  await vscode.commands.executeCommand("setContext", "aguafria.connected", false);
  output.appendLine("Editor disconnected; the authoritative Aguafria runtime is unchanged");
}

async function readyClient(): Promise<{ client: NReplClient; token: string; handshake: Handshake }> {
  if (!client?.connected || token === undefined || !handshake) await startDevelopmentProgram();
  if (!client || token === undefined || !handshake) throw new Error("Aguafria failed to connect");
  return { client, token, handshake };
}

function vscodePosition(position: vscode.Position): { line: number; character: number } {
  return { line: position.line, character: position.character };
}

function sourceHash(source: string): string {
  return createHash("sha256").update(source, "utf8").digest("hex");
}

function vscodeRange(range: vscode.Range): ObjectValue {
  return {
    start: vscodePosition(range.start),
    end: vscodePosition(range.end),
  } as unknown as ObjectValue;
}

function diagnosticRange(value: BValue | undefined): vscode.Range {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return new vscode.Range(0, 0, 0, 1);
  }
  const range = value as ObjectValue;
  const position = (candidate: BValue | undefined): vscode.Position => {
    const object = typeof candidate === "object" && candidate !== null && !Array.isArray(candidate)
      ? (candidate as ObjectValue)
      : {};
    return new vscode.Position(Number(object.line ?? 0), Number(object.character ?? 0));
  };
  return new vscode.Range(position(range.start), position(range.end));
}

function publishDiagnostics(uri: vscode.Uri, ticket: ObjectValue): void {
  const openDocument = vscode.workspace.textDocuments.find((document) => document.uri.toString() === uri.toString());
  if (openDocument && Number(ticket["document-version"]) !== openDocument.version) return;
  const values = ticket.diagnostics;
  if (!Array.isArray(values)) return;
  const converted = values.map((value) => {
    const detail = asObject(value);
    const item = new vscode.Diagnostic(
      diagnosticRange(detail.range),
      String(detail.message ?? "Aguafria hot reload failed"),
      vscode.DiagnosticSeverity.Error,
    );
    item.source = "Aguafria Hot Reload";
    item.code = String(detail.code ?? "zig-editor-error");
    return item;
  });
  diagnostics.set(uri, converted);
}

async function evaluate(mode: "declaration" | "selection" | "changed" | "file"): Promise<ObjectValue> {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== "zig") {
    throw new Error("Focus a Zig editor before evaluating");
  }
  const connection = await readyClient();
  const selection = editor.selection;
  const source = editor.document.getText();
  const uri = editor.document.uri.toString();
  statusBar.text = "$(loading~spin) Aguafria evaluating";
  const result = asObject(resultFrom(await connection.client.request({
    op: mode === "file" ? "aguafria/eval-zig-file" : "aguafria/eval-zig",
    token: connection.token,
    "project-id": connection.handshake.projectId,
    "runtime-id": connection.handshake.runtimeId,
    uri,
    source,
    "source-hash": sourceHash(source),
    "base-source-hash": acceptedSourceHashes.get(uri),
    "document-version": editor.document.version,
    mode,
    position: vscodePosition(selection.active),
    range: vscodeRange(selection),
  })));
  const initialStatus = statusName(result.status);
  latestTicketId = typeof result["ticket-id"] === "string" ? result["ticket-id"] : latestTicketId;
  if (initialStatus === "failed") {
    publishDiagnostics(editor.document.uri, result);
    statusBar.text = "$(error) Aguafria reload failed; old generation active";
    output.appendLine(JSON.stringify(result, null, 2));
    throw new Error(String(asObject((result.diagnostics as BValue[])[0] as BValue).message));
  }
  if (typeof result["source-hash"] === "string") {
    acceptedSourceHashes.set(uri, result["source-hash"] as string);
  }
  const awaitPublication = vscode.workspace.getConfiguration("aguafria").get<boolean>("awaitPublication", true);
  const completed = awaitPublication
    ? asObject(resultFrom(await connection.client.request({
        op: "aguafria/await",
        token: connection.token,
        "runtime-id": connection.handshake.runtimeId,
        "ticket-id": result["ticket-id"],
      })))
    : result;
  if (statusName(completed.status) === "failed") {
    publishDiagnostics(editor.document.uri, completed);
    statusBar.text = "$(error) Aguafria reload failed; old generation active";
  } else {
    if (Number(completed["document-version"]) === editor.document.version) {
      diagnostics.delete(editor.document.uri);
    }
    const generation = completed["published-generation"] ?? completed["requested-generation"] ?? "?";
    statusBar.text = `$(check) Aguafria generation ${generation}`;
  }
  output.appendLine(JSON.stringify(completed, null, 2));
  monitor.refresh(completed);
  return completed;
}

async function interruptEvaluation(): Promise<void> {
  if (!latestTicketId) throw new Error("No Aguafria evaluation ticket is available to interrupt");
  const connection = await readyClient();
  const result = asObject(resultFrom(await connection.client.request({
    op: "aguafria/interrupt",
    token: connection.token,
    "runtime-id": connection.handshake.runtimeId,
    "ticket-id": latestTicketId,
  })));
  output.appendLine(`Interrupt ${latestTicketId}: ${JSON.stringify(result)}`);
  const status = statusName(result.status);
  statusBar.text = status === "cancelled"
    ? "$(circle-slash) Aguafria evaluation cancelled"
    : `$(info) Aguafria ${status}`;
}

async function showStatus(): Promise<void> {
  const connection = await readyClient();
  const value = asObject(resultFrom(await connection.client.request({
    op: "aguafria/status",
    token: connection.token,
    "runtime-id": connection.handshake.runtimeId,
  })));
  output.appendLine(JSON.stringify(value, null, 2));
  output.show(true);
  monitor.refresh(value);
}

async function showSource(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor) throw new Error("Focus a Zig editor first");
  const connection = await readyClient();
  const value = asObject(resultFrom(await connection.client.request({
    op: "aguafria/source",
    token: connection.token,
    "project-id": connection.handshake.projectId,
    "runtime-id": connection.handshake.runtimeId,
    uri: editor.document.uri.toString(),
  })));
  const document = await vscode.workspace.openTextDocument({
    language: "zig",
    content: String(value.source ?? ""),
  });
  await vscode.window.showTextDocument(document, { preview: true, viewColumn: vscode.ViewColumn.Beside });
}

async function invokeFunction(
  document: vscode.TextDocument,
  functionName: string,
  argumentsValue: unknown[],
): Promise<BValue> {
  const connection = await readyClient();
  return resultFrom(await connection.client.request({
    op: "aguafria/invoke",
    token: connection.token,
    "project-id": connection.handshake.projectId,
    "runtime-id": connection.handshake.runtimeId,
    uri: document.uri.toString(),
    function: functionName,
    arguments: argumentsValue,
  }));
}

async function programStatus(): Promise<ObjectValue> {
  const connection = await readyClient();
  return asObject(resultFrom(await connection.client.request({
    op: "aguafria/program-status",
    token: connection.token,
    "project-id": connection.handshake.projectId,
    "runtime-id": connection.handshake.runtimeId,
  })));
}

async function invoke(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor) throw new Error("Focus a Zig editor first");
  const functionName = await vscode.window.showInputBox({ prompt: "Zig function name", placeHolder: "answer" });
  if (!functionName) return;
  const argumentsText = await vscode.window.showInputBox({ prompt: "Arguments as JSON array", value: "[]" });
  if (argumentsText === undefined) return;
  const argumentsValue = JSON.parse(argumentsText) as unknown[];
  const value = await invokeFunction(editor.document, functionName, argumentsValue);
  output.appendLine(`=> ${JSON.stringify(value)}`);
  output.show(true);
}

async function inspectDeclaration(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== "zig") {
    throw new Error("Focus a Zig editor before inspecting a declaration");
  }
  const connection = await readyClient();
  const inspected = asObject(resultFrom(await connection.client.request({
    op: "aguafria/value",
    token: connection.token,
    "project-id": connection.handshake.projectId,
    "runtime-id": connection.handshake.runtimeId,
    uri: editor.document.uri.toString(),
    source: editor.document.getText(),
    position: vscodePosition(editor.selection.active),
  })));
  output.appendLine(`=> ${JSON.stringify(inspected, null, 2)}`);
  output.show(true);
  monitor.refresh(inspected);
}

async function showHistory(): Promise<void> {
  const connection = await readyClient();
  const value = resultFrom(await connection.client.request({
    op: "aguafria/history",
    token: connection.token,
    "runtime-id": connection.handshake.runtimeId,
  }));
  output.appendLine(JSON.stringify(value, null, 2));
  output.show(true);
}

class HotReloadTree implements vscode.TreeDataProvider<vscode.TreeItem> {
  private readonly changed = new vscode.EventEmitter<vscode.TreeItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private latest: ObjectValue | undefined;

  refresh(value: ObjectValue): void {
    this.latest = value;
    this.changed.fire(undefined);
  }

  getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(): vscode.TreeItem[] {
    if (!this.latest) return [new vscode.TreeItem("No publication yet")];
    const entries = Object.entries(this.latest).slice(0, 30);
    return entries.map(([key, value]) => {
      const item = new vscode.TreeItem(key);
      item.description = typeof value === "string" || typeof value === "number"
        ? String(value)
        : Array.isArray(value)
          ? `${value.length} items`
          : "inspect in output";
      return item;
    });
  }
}

function command(callback: () => Promise<unknown>): () => Promise<void> {
  return async () => {
    try {
      await callback();
    } catch (error) {
      output.appendLine(error instanceof Error ? error.stack ?? error.message : String(error));
      vscode.window.showErrorMessage(error instanceof Error ? error.message : String(error));
    }
  };
}

export async function activate(extensionContext: vscode.ExtensionContext): Promise<object> {
  context = extensionContext;
  output = vscode.window.createOutputChannel("Aguafria Zig", { log: true });
  diagnostics = vscode.languages.createDiagnosticCollection("aguafria-hot-reload");
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  statusBar.command = "aguafria.showStatus";
  statusBar.text = "$(debug-disconnect) Aguafria disconnected";
  statusBar.show();
  monitor = new HotReloadTree();
  extensionContext.subscriptions.push(
    output,
    diagnostics,
    statusBar,
    vscode.window.registerTreeDataProvider("aguafria.hotReload", monitor),
    vscode.commands.registerCommand("aguafria.startDevelopmentProgram", command(startDevelopmentProgram)),
    vscode.commands.registerCommand("aguafria.jackIn", command(startDevelopmentProgram)),
    vscode.commands.registerCommand("aguafria.connect", command(connect)),
    vscode.commands.registerCommand("aguafria.disconnect", command(disconnectEditor)),
    vscode.commands.registerCommand("aguafria.stopDevelopmentProgram", command(stopDevelopmentProgram)),
    vscode.commands.registerCommand("aguafria.evaluateDeclaration", command(() => evaluate("declaration"))),
    vscode.commands.registerCommand("aguafria.evaluateSelection", command(() => evaluate("selection"))),
    vscode.commands.registerCommand("aguafria.evaluateChanged", command(() => evaluate("changed"))),
    vscode.commands.registerCommand("aguafria.evaluateFile", command(() => evaluate("file"))),
    vscode.commands.registerCommand("aguafria.interrupt", command(interruptEvaluation)),
    vscode.commands.registerCommand("aguafria.showStatus", command(showStatus)),
    vscode.commands.registerCommand("aguafria.showSource", command(showSource)),
    vscode.commands.registerCommand("aguafria.invoke", command(invoke)),
    vscode.commands.registerCommand("aguafria.inspect", command(inspectDeclaration)),
    vscode.commands.registerCommand("aguafria.showHistory", command(showHistory)),
  );
  return {
    startDevelopmentProgram,
    connect,
    evaluate,
    invokeFunction,
    programStatus,
    showStatus,
    connected: () => Boolean(client?.connected),
    runtimeIdentity: () => handshake ? identityFrom(handshake) : undefined,
  };
}

export function deactivate(): void {
  client?.close();
  const keep = vscode.workspace.getConfiguration("aguafria").get<boolean>("keepRuntimeOnDeactivate", true);
  if (!keep && ownedServer && ownedServer.exitCode === null) ownedServer.kill("SIGTERM");
}

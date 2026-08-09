import * as net from "node:net";
import { randomUUID } from "node:crypto";

export type BValue = string | number | BValue[] | { [key: string]: BValue };

const maximumBufferedResponseBytes = 64 * 1024 * 1024;

class IncompleteValue extends Error {}

function parseAt(buffer: Buffer, offset: number): [BValue, number] {
  if (offset >= buffer.length) {
    throw new IncompleteValue();
  }
  const marker = buffer[offset];
  if (marker === 0x69) {
    const end = buffer.indexOf(0x65, offset + 1);
    if (end < 0) throw new IncompleteValue();
    const text = buffer.subarray(offset + 1, end).toString("ascii");
    if (!/^-?(0|[1-9][0-9]*)$/.test(text)) {
      throw new Error(`Invalid bencode integer: ${text}`);
    }
    return [Number.parseInt(text, 10), end + 1];
  }
  if (marker === 0x6c) {
    const result: BValue[] = [];
    let cursor = offset + 1;
    while (true) {
      if (cursor >= buffer.length) throw new IncompleteValue();
      if (buffer[cursor] === 0x65) return [result, cursor + 1];
      const [value, next] = parseAt(buffer, cursor);
      result.push(value);
      cursor = next;
    }
  }
  if (marker === 0x64) {
    const result: { [key: string]: BValue } = {};
    let cursor = offset + 1;
    while (true) {
      if (cursor >= buffer.length) throw new IncompleteValue();
      if (buffer[cursor] === 0x65) return [result, cursor + 1];
      const [key, afterKey] = parseAt(buffer, cursor);
      if (typeof key !== "string") throw new Error("Bencode dictionary key is not text");
      const [value, afterValue] = parseAt(buffer, afterKey);
      result[key] = value;
      cursor = afterValue;
    }
  }
  if (marker !== undefined && marker >= 0x30 && marker <= 0x39) {
    const colon = buffer.indexOf(0x3a, offset);
    if (colon < 0) throw new IncompleteValue();
    const lengthText = buffer.subarray(offset, colon).toString("ascii");
    if (!/^(0|[1-9][0-9]*)$/.test(lengthText)) {
      throw new Error(`Invalid bencode byte-string length: ${lengthText}`);
    }
    const length = Number.parseInt(lengthText, 10);
    const end = colon + 1 + length;
    if (end > buffer.length) throw new IncompleteValue();
    return [buffer.subarray(colon + 1, end).toString("utf8"), end];
  }
  throw new Error(`Invalid bencode marker 0x${marker?.toString(16) ?? "EOF"}`);
}

export function decodeOne(buffer: Buffer): { value: BValue; bytes: number } | undefined {
  try {
    const [value, bytes] = parseAt(buffer, 0);
    return { value, bytes };
  } catch (error) {
    if (error instanceof IncompleteValue) return undefined;
    throw error;
  }
}

function encodeText(value: string): Buffer {
  const bytes = Buffer.from(value, "utf8");
  return Buffer.concat([Buffer.from(`${bytes.length}:`, "ascii"), bytes]);
}

export function encode(value: unknown): Buffer {
  if (value === undefined || value === null) return Buffer.from("le", "ascii");
  if (typeof value === "boolean") return Buffer.from(value ? "i1e" : "i0e", "ascii");
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) return encodeText(String(value));
    return Buffer.from(`i${value}e`, "ascii");
  }
  if (typeof value === "string") return encodeText(value);
  if (Buffer.isBuffer(value)) {
    return Buffer.concat([Buffer.from(`${value.length}:`, "ascii"), value]);
  }
  if (Array.isArray(value)) {
    return Buffer.concat([Buffer.from("l"), ...value.map(encode), Buffer.from("e")]);
  }
  if (value instanceof Set) return encode([...value]);
  if (typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => item !== undefined)
      .sort(([left], [right]) => Buffer.compare(Buffer.from(left), Buffer.from(right)));
    return Buffer.concat([
      Buffer.from("d"),
      ...entries.flatMap(([key, item]) => [encodeText(key), encode(item)]),
      Buffer.from("e"),
    ]);
  }
  return encodeText(String(value));
}

export type Response = { [key: string]: BValue };

type Pending = {
  responses: Response[];
  resolve: (responses: Response[]) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
};

function isDone(response: Response): boolean {
  const status = response.status;
  return Array.isArray(status) && status.some((item) => item === "done");
}

export class NReplClient {
  private socket: net.Socket | undefined;
  private incoming = Buffer.alloc(0);
  private readonly pending = new Map<string, Pending>();

  async connect(host: string, port: number): Promise<void> {
    if (this.socket && !this.socket.destroyed) return;
    await new Promise<void>((resolve, reject) => {
      const socket = net.createConnection({ host, port });
      const fail = (error: Error) => {
        socket.destroy();
        reject(error);
      };
      socket.once("error", fail);
      socket.once("connect", () => {
        socket.off("error", fail);
        this.socket = socket;
        socket.on("data", (chunk) => this.receive(chunk));
        socket.on("error", (error) => this.failAll(error));
        socket.on("close", () => this.failAll(new Error("nREPL connection closed")));
        resolve();
      });
    });
  }

  get connected(): boolean {
    return Boolean(this.socket && !this.socket.destroyed);
  }

  request(message: Record<string, unknown>, timeoutMs = 120_000): Promise<Response[]> {
    if (!this.socket || this.socket.destroyed) {
      return Promise.reject(new Error("Aguafria nREPL is not connected"));
    }
    const id = typeof message.id === "string" ? message.id : randomUUID();
    return new Promise<Response[]>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Timed out waiting for nREPL request ${id}`));
      }, timeoutMs);
      this.pending.set(id, { responses: [], resolve, reject, timer });
      this.socket?.write(encode({ "protocol-version": 1, ...message, id }));
    });
  }

  close(): void {
    this.socket?.destroy();
    this.socket = undefined;
    this.failAll(new Error("Aguafria nREPL client closed"));
  }

  private receive(chunk: Buffer): void {
    this.incoming = Buffer.concat([this.incoming, chunk]);
    if (this.incoming.length > maximumBufferedResponseBytes) {
      const error = new Error("Aguafria nREPL response exceeds the 64 MiB safety limit");
      this.socket?.destroy(error);
      this.incoming = Buffer.alloc(0);
      this.failAll(error);
      return;
    }
    while (this.incoming.length > 0) {
      const decoded = decodeOne(this.incoming);
      if (!decoded) return;
      this.incoming = this.incoming.subarray(decoded.bytes);
      if (typeof decoded.value !== "object" || Array.isArray(decoded.value)) continue;
      const response = decoded.value as Response;
      const id = response.id;
      if (typeof id !== "string") continue;
      const pending = this.pending.get(id);
      if (!pending) continue;
      pending.responses.push(response);
      if (isDone(response)) {
        clearTimeout(pending.timer);
        this.pending.delete(id);
        pending.resolve(pending.responses);
      }
    }
  }

  private failAll(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}

export function resultFrom(responses: Response[]): BValue {
  const error = responses.find((response) => response["aguafria/error"] !== undefined);
  if (error) {
    const detail = error["aguafria/error"];
    const message =
      typeof detail === "object" && !Array.isArray(detail) && detail !== null
        ? detail.message
        : undefined;
    throw new Error(typeof message === "string" ? message : "Aguafria nREPL operation failed");
  }
  const response = [...responses].reverse().find((item) => item["aguafria/result"] !== undefined);
  if (!response) throw new Error("Aguafria nREPL returned no result");
  return response["aguafria/result"] as BValue;
}

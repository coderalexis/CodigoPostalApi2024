import createClient from "openapi-fetch";
import type { paths } from "./schema.js";
export type { paths, components, operations } from "./schema.js";

export class PostalApiError extends Error {
  constructor(public readonly status: number, message: string,
    public readonly body: unknown, public readonly retryAfterMs: number | undefined,
    public readonly headers: Headers) { super(message); this.name = "PostalApiError"; }
}

export function retryAfterMs(headers: Headers, now = Date.now()): number | undefined {
  const value = headers.get("Retry-After") ?? headers.get("X-RateLimit-Retry-After-Seconds");
  if (value === null) return undefined;
  if (/^[0-9]+$/.test(value.trim())) return Number(value) * 1000;
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - now) : undefined;
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const abort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    signal.addEventListener("abort", abort, { once: true });
  });
}

export interface ClientOptions {
  baseUrl: string;
  fetch?: typeof globalThis.fetch;
  /** Reintentos de GET/HEAD con HTTP 429; nunca de POST. Predeterminado: 1. */
  maxRetries?: number;
  /** Si Retry-After supera este límite, devuelve el error sin reintentar antes de tiempo. */
  maxRetryDelayMs?: number;
}

export function createPostalClient(options: ClientOptions) {
  const retries = options.maxRetries ?? 1;
  const maxDelay = options.maxRetryDelayMs ?? 30_000;
  if (!Number.isInteger(retries) || retries < 0 || retries > 5 || !Number.isFinite(maxDelay) || maxDelay < 0)
    throw new RangeError("Configuración de reintentos inválida");
  const fetcher = options.fetch ?? globalThis.fetch;
  return createClient<paths>({ baseUrl: options.baseUrl, fetch: async (request: Request) => {
    let response: Response;
    for (let attempt = 0; ; attempt++) {
      request.signal.throwIfAborted();
      response = await fetcher(request.clone());
      const delay = retryAfterMs(response.headers) ?? 1000;
      if (response.status !== 429 || !["GET", "HEAD"].includes(request.method) || attempt >= retries || delay > maxDelay) break;
      await response.body?.cancel();
      await sleep(delay, request.signal);
    }
    if (!response.ok) {
      const text = await response.text();
      let body: unknown;
      try { body = JSON.parse(text); } catch { body = text; }
      const message = body && typeof body === "object" && "message" in body && typeof body.message === "string"
        ? body.message : `HTTP ${response.status}`;
      throw new PostalApiError(response.status, message, body, retryAfterMs(response.headers), response.headers);
    }
    return response;
  }});
}

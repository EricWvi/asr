export type User = {
  name: string;
  email: string;
};

export const COLORS = ["butter", "peach", "rose", "periwinkle", "sky", "mint"] as const;
export type DocColor = (typeof COLORS)[number];

/** A document as listed in the sidebar, without its content. */
export type DocSummary = {
  id: number;
  title: string;
  color: DocColor;
  snippet?: string;
  created_at: string;
  updated_at: string;
};

/** [from, to) offsets into a document's content. */
export type Span = [number, number];

/** tidy_ranges is the dictated text awaiting tidying. */
export type Doc = DocSummary & { content: string; tidy_ranges?: Span[] };

export type DocPatch = Partial<Pick<Doc, "title" | "color" | "content" | "tidy_ranges">>;

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(method: string, path: string, body?: unknown, keepalive = false): Promise<T> {
  const init: RequestInit = { method, cache: "no-store", keepalive };
  if (body instanceof Blob) {
    init.body = body;
  } else if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers = { "Content-Type": "application/json" };
  }
  let res: Response;
  try {
    res = await fetch(path, init);
  } catch {
    throw new ApiError(0, navigator.onLine ? "无法连接服务器" : "当前处于离线状态");
  }
  const data: unknown = await res.json().catch(() => null);
  if (!res.ok) {
    const message = (data as { error?: string } | null)?.error ?? `请求失败（${res.status}）`;
    throw new ApiError(res.status, message);
  }
  return data as T;
}

export const api = {
  /** Uploads pasted or dropped image bytes; the URL goes into the Markdown. */
  uploadImage: (file: Blob) => request<{ id: string; url: string }>("POST", "/api/images", file),
  me: () => request<User>("GET", "/api/me"),
  documents: () => request<DocSummary[]>("GET", "/api/documents"),
  /** Documents holding every word; the snippet is the line around the first hit. */
  search: (q: string) => request<DocSummary[]>("GET", `/api/search?q=${encodeURIComponent(q)}`),
  // The server leaves out empty content.
  document: (id: number) =>
    request<Doc>("GET", `/api/documents/${id}`).then((d) => ({ ...d, content: d.content ?? "" })),
  createDocument: (title: string) => request<Doc>("POST", "/api/documents", { title }),
  /** keepalive lets a small save finish while the page unloads. */
  updateDocument: (id: number, patch: DocPatch, keepalive = false) =>
    request<DocSummary>("PATCH", `/api/documents/${id}`, patch, keepalive),
  deleteDocument: (id: number) => request<null>("DELETE", `/api/documents/${id}`),
  restoreDocument: (id: number) => request<null>("POST", `/api/documents/${id}/restore`),
};

export type TidyRequest = { text: string; headings: string[]; prefix: string; suffix: string };

/**
 * Asks the server to tidy a transcript and returns the written text once
 * the model has finished; the server streams it as JSON lines.
 */
export async function tidyText(req: TidyRequest, signal: AbortSignal): Promise<string> {
  let res: Response;
  try {
    res = await fetch("/api/tidy", {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req),
      signal,
    });
  } catch (err) {
    if (signal.aborted) throw err;
    throw new ApiError(0, navigator.onLine ? "无法连接服务器" : "当前处于离线状态");
  }
  if (!res.ok || !res.body) {
    const data = (await res.json().catch(() => null)) as { error?: string } | null;
    throw new ApiError(res.status, data?.error ?? `请求失败（${res.status}）`);
  }
  const lines = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let out = "";
  let rest = "";
  try {
    for (;;) {
      const { value, done } = await lines.read();
      if (done) break;
      rest += value;
      const parts = rest.split("\n");
      rest = parts.pop() ?? "";
      for (const line of parts) {
        if (!line.trim()) continue;
        const m = JSON.parse(line) as { type: string; text?: string; message?: string };
        if (m.type === "delta") out += m.text ?? "";
        else if (m.type === "error") throw new ApiError(502, m.message ?? "整理失败，请稍后再试");
        else if (m.type === "done") return out;
      }
    }
  } catch (err) {
    if (signal.aborted || err instanceof ApiError) throw err;
  }
  throw new ApiError(0, "整理中断，请稍后再试");
}

/** Ends this browser's session on the server. */
export async function logout() {
  let res: Response;
  try {
    res = await fetch("/auth/logout", { method: "POST", cache: "no-store" });
  } catch {
    throw new ApiError(0, "当前处于离线状态，无法退出登录");
  }
  if (!res.ok) throw new ApiError(res.status, "退出登录失败");
}

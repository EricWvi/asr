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

export type Doc = DocSummary & { content: string };

export type DocPatch = Partial<Pick<Doc, "title" | "color" | "content">>;

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
  me: () => request<User>("GET", "/api/me"),
  documents: () => request<DocSummary[]>("GET", "/api/documents"),
  document: (id: number) => request<Doc>("GET", `/api/documents/${id}`),
  createDocument: (title: string) => request<Doc>("POST", "/api/documents", { title }),
  /** keepalive lets a small save finish while the page unloads. */
  updateDocument: (id: number, patch: DocPatch, keepalive = false) =>
    request<DocSummary>("PATCH", `/api/documents/${id}`, patch, keepalive),
  deleteDocument: (id: number) => request<null>("DELETE", `/api/documents/${id}`),
  restoreDocument: (id: number) => request<null>("POST", `/api/documents/${id}/restore`),
};

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

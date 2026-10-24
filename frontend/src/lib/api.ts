export type User = {
  name: string;
  email: string;
};

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, cache: "no-store" };
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

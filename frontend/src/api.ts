const TOKEN_KEY = "laundry_token";

export const getToken = () => localStorage.getItem(TOKEN_KEY);
export const setToken = (token: string | null) =>
  token ? localStorage.setItem(TOKEN_KEY, token) : localStorage.removeItem(TOKEN_KEY);

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function fail(res: Response): Promise<never> {
  const data = await res.json().catch(() => null);
  const detail = data?.detail;
  const message =
    typeof detail === "string"
      ? detail
      : Array.isArray(detail)
        ? detail.map((d: { msg: string }) => d.msg).join("; ")
        : `Ошибка ${res.status}`;
  if (res.status === 401 && getToken()) {
    // токен протух — на страницу входа
    setToken(null);
    window.location.assign("/login");
  }
  throw new ApiError(message, res.status);
}

export async function api<T = unknown>(
  path: string,
  opts: { method?: string; body?: unknown; form?: FormData } = {},
): Promise<T> {
  const headers = authHeaders();
  let body: BodyInit | undefined;
  if (opts.form) {
    body = opts.form;
  } else if (opts.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(opts.body);
  }
  const res = await fetch("/api" + path, { method: opts.method ?? "GET", headers, body });
  if (!res.ok) await fail(res);
  return res.json();
}

export async function download(path: string, filename: string) {
  const res = await fetch("/api" + path, { headers: authHeaders() });
  if (!res.ok) await fail(res);
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export const errorText = (e: unknown) => (e instanceof Error ? e.message : "Что-то пошло не так");

// ---------- типы ----------

export interface User {
  id: number;
  username: string;
  display_name: string;
}

export type Role = "owner" | "admin" | "member";

export interface Laundry {
  code: string;
  name: string;
  description: string;
  capacity: number;
  member_count: number;
  my_role: Role | null;
  my_status: "pending" | "active" | null;
}

export interface Member {
  user_id: number;
  username: string;
  display_name: string;
  role: Role;
  status: "pending" | "active";
}

export interface Booking {
  id: number;
  user_id: number | null;
  name: string;
  start: string; // местное время стиралки, "YYYY-MM-DDTHH:MM:SS"
  end: string;
  note: string;
  mine: boolean;
}

export interface Schedule {
  date_from: string;
  date_to: string;
  now: string;
  bookings: Booking[];
}

export interface ImportRow {
  booking_id: number | null;
  date: string;
  start: string;
  end: string;
  member: string;
  note: string;
  skip: boolean;
}

export interface ImportConflict {
  kind: "existing" | "row";
  booking_id: number | null;
  index: number | null;
  label: string;
  start: string;
  end: string;
}

export interface ImportResult {
  status: "new" | "update" | "unchanged" | "conflict" | "error" | "skipped";
  message: string;
  resolved_name: string;
  is_guest: boolean;
  conflicts: ImportConflict[];
}

export interface ImportOut {
  rows: ImportRow[];
  results: ImportResult[];
  applied: boolean;
  ignored: number;
  missing: ImportConflict[];
  created: number;
  updated: number;
  deleted: number;
}

export const ROLE_LABEL: Record<Role, string> = {
  owner: "создатель",
  admin: "админ",
  member: "участник",
};

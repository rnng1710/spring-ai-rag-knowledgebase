import { apiUrl, authFetch } from "./client";

export type UserRole = "ADMIN" | "USER";

export interface User {
  id: string;
  username: string;
  role: UserRole;
  enabled: boolean;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  role: UserRole;
}

interface ApiEnvelope<T> {
  code?: number;
  msg?: string;
  message?: string;
  data?: T;
}

const parseEnvelope = async <T>(res: Response): Promise<T> => {
  const text = await res.text();
  let json: any;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }

  if (!res.ok) {
    const msg = (json?.msg || json?.message || text || "Request failed").toString();
    throw new Error(`HTTP ${res.status}: ${msg}`);
  }

  // Backend usually returns { code: 0, data, msg }
  const envelope = json as ApiEnvelope<T>;
  if (envelope && typeof envelope === "object" && "code" in envelope) {
    if (envelope.code === 0) return envelope.data as T;
    throw new Error((envelope.msg || envelope.message || "Request failed") as string);
  }

  // Fallback: some endpoints may return raw payload
  return json as T;
};

// NOTE: Adjust these paths if your backend uses a different user-management prefix.
const USERS_BASE = "/api/v1/admin/users";

export const getUsers = async () => {
  const res = await authFetch(apiUrl(USERS_BASE));
  return await parseEnvelope<User[]>(res);
};

export const createUser = async (payload: CreateUserRequest) => {
  const res = await authFetch(apiUrl(USERS_BASE), {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
  return await parseEnvelope<void>(res);
};

export const deleteUser = async (id: string | number) => {
  const res = await authFetch(apiUrl(`${USERS_BASE}/${encodeURIComponent(String(id))}`), {
    method: "DELETE"
  });
  return await parseEnvelope<void>(res);
};

export const resetUserPassword = async (id: string | number, newPassword: string) => {
  const res = await authFetch(apiUrl(`${USERS_BASE}/${encodeURIComponent(String(id))}/password`), {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ password: newPassword })
  });
  return await parseEnvelope<void>(res);
};

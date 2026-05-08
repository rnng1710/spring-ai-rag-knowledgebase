import { clearTokens } from "../api/client";

export type AppRole = "ADMIN" | "USER";

export interface JwtPayload {
  sub?: string;
  roles?: string[];
  deptId?: string;
  deptName?: string;
  exp?: number;
  [key: string]: unknown;
}

const ACCESS_TOKEN_KEY = "auth_access_token";

const decodeBase64Url = (value: string): string | null => {
  try {
    const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), "=");
    return atob(padded);
  } catch {
    return null;
  }
};

export const decodeJwtPayload = (token: string): JwtPayload | null => {
  if (!token) {
    return null;
  }

  const parts = token.split(".");
  if (parts.length < 2) {
    return null;
  }

  const payloadText = decodeBase64Url(parts[1]);
  if (!payloadText) {
    return null;
  }

  try {
    const payload = JSON.parse(payloadText);
    if (!payload || typeof payload !== "object") {
      return null;
    }
    return payload as JwtPayload;
  } catch {
    return null;
  }
};

export const getRoleFromAccessToken = (token: string | null): AppRole | null => {
  if (!token) {
    return null;
  }

  const payload = decodeJwtPayload(token);
  if (!payload || !Array.isArray(payload.roles)) {
    return null;
  }

  const normalized = payload.roles
    .filter((role): role is string => typeof role === "string")
    .map((role) => role.toUpperCase());

  if (normalized.includes("ADMIN")) {
    return "ADMIN";
  }
  if (normalized.includes("USER")) {
    return "USER";
  }
  return null;
};

export const getUsernameFromAccessToken = (token: string | null): string | null => {
  if (!token) {
    return null;
  }
  const payload = decodeJwtPayload(token);
  if (!payload || typeof payload.sub !== "string" || !payload.sub.trim()) {
    return null;
  }
  return payload.sub;
};

export const isTokenExpired = (token: string | null): boolean => {
  if (!token) {
    return true;
  }
  const payload = decodeJwtPayload(token);
  if (!payload || typeof payload.exp !== "number") {
    return true;
  }
  const nowInSeconds = Math.floor(Date.now() / 1000);
  return payload.exp <= nowInSeconds;
};

export const getAccessToken = (): string | null => localStorage.getItem(ACCESS_TOKEN_KEY);

export const clearAuthSession = (): void => {
  clearTokens();
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
};

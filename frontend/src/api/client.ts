const API_BASE = import.meta.env.VITE_API_BASE || "";

const KEY_ACCESS_TOKEN = "auth_access_token";
const KEY_REFRESH_TOKEN = "auth_refresh_token";

interface JwtPayload {
  exp?: number;
}

interface TokenResponse {
  access_token: string;
  refresh_token: string;
}

let refreshPromise: Promise<TokenResponse> | null = null;

const getStoredAccessToken = () => localStorage.getItem(KEY_ACCESS_TOKEN);

const decodeBase64Url = (value: string): string | null => {
  try {
    const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), "=");
    return atob(padded);
  } catch {
    return null;
  }
};

const decodeJwtPayload = (token: string): JwtPayload | null => {
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
    return payload && typeof payload === "object" ? (payload as JwtPayload) : null;
  } catch {
    return null;
  }
};

const isTokenExpired = (token: string | null): boolean => {
  if (!token) {
    return true;
  }

  const payload = decodeJwtPayload(token);
  if (!payload || typeof payload.exp !== "number") {
    return true;
  }

  const nowInSeconds = Math.floor(Date.now() / 1000);
  return payload.exp <= nowInSeconds + 30;
};

const parseTokenEnvelope = async (response: Response): Promise<TokenResponse> => {
  const json = await response.json();
  if (!response.ok || json?.code !== 0 || !json?.data?.access_token || !json?.data?.refresh_token) {
    const message = json?.msg || json?.message || `Request failed: ${response.status}`;
    throw new Error(message);
  }
  return json.data as TokenResponse;
};

export const getAuthHeader = () => {
  const token = getStoredAccessToken();
  return token ? `Bearer ${token}` : "";
};

export const getAccessToken = () => getStoredAccessToken();

export const getRefreshToken = () => localStorage.getItem(KEY_REFRESH_TOKEN);

export const setTokens = (accessToken: string, refreshToken: string) => {
  localStorage.setItem(KEY_ACCESS_TOKEN, accessToken);
  localStorage.setItem(KEY_REFRESH_TOKEN, refreshToken);
};

export const clearTokens = () => {
  localStorage.removeItem(KEY_ACCESS_TOKEN);
  localStorage.removeItem(KEY_REFRESH_TOKEN);
};

const clearStoredSession = () => {
  clearTokens();
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
};

export const apiUrl = (path: string) => `${API_BASE}${path}`;

export const refreshTokens = async (): Promise<TokenResponse> => {
  if (refreshPromise) {
    return refreshPromise;
  }

  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    throw new Error("Refresh token missing");
  }

  refreshPromise = (async () => {
    try {
      const response = await fetch(apiUrl("/api/v1/auth/refresh"), {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${refreshToken}`,
        },
        body: JSON.stringify({ refresh_token: refreshToken }),
      });

      const tokens = await parseTokenEnvelope(response);
      setTokens(tokens.access_token, tokens.refresh_token);
      return tokens;
    } catch (error) {
      clearStoredSession();
      throw error;
    }
  })();

  try {
    return await refreshPromise;
  } finally {
    refreshPromise = null;
  }
};

export const ensureValidAccessToken = async (): Promise<string> => {
  const accessToken = getStoredAccessToken();
  if (accessToken && !isTokenExpired(accessToken)) {
    return accessToken;
  }

  const tokens = await refreshTokens();
  return tokens.access_token;
};

export const authFetch = async (input: string, init: RequestInit = {}, allowRetry = true): Promise<Response> => {
  const headers = new Headers(init.headers || {});
  headers.set("Authorization", `Bearer ${await ensureValidAccessToken()}`);

  const response = await fetch(input, {
    ...init,
    headers,
  });

  if (response.status !== 401 || !allowRetry) {
    return response;
  }

  const tokens = await refreshTokens();
  headers.set("Authorization", `Bearer ${tokens.access_token}`);
  return fetch(input, {
    ...init,
    headers,
  });
};

export const loginApi = async (username: string, password: string) => {
  const response = await fetch(apiUrl("/api/v1/auth/login"), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) {
    throw new Error(`Login failed: ${response.status}`);
  }

  return await parseTokenEnvelope(response);
};

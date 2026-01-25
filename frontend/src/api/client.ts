const API_BASE = import.meta.env.VITE_API_BASE || "";

const KEY_ACCESS_TOKEN = "auth_access_token";
const KEY_REFRESH_TOKEN = "auth_refresh_token";

export const getAuthHeader = () => {
  const token = localStorage.getItem(KEY_ACCESS_TOKEN);
  return token ? `Bearer ${token}` : "";
};

export const setTokens = (accessToken: string, refreshToken: string) => {
  localStorage.setItem(KEY_ACCESS_TOKEN, accessToken);
  localStorage.setItem(KEY_REFRESH_TOKEN, refreshToken);
};

export const clearTokens = () => {
  localStorage.removeItem(KEY_ACCESS_TOKEN);
  localStorage.removeItem(KEY_REFRESH_TOKEN);
};

export const apiUrl = (path: string) => `${API_BASE}${path}`;

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

  const json = await response.json();
  if (json.code === 0) {
    return json.data; // { access_token, refresh_token }
  } else {
    throw new Error(json.message || "Login failed");
  }
};

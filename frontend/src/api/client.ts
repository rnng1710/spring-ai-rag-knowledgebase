const API_BASE = import.meta.env.VITE_API_BASE || "";

export const getAuthHeader = () => {
  const encoded = localStorage.getItem("auth_basic");
  return encoded ? `Basic ${encoded}` : "";
};

export const ensureAuth = (username: string, password: string) => {
  const token = btoa(`${username}:${password}`);
  localStorage.setItem("auth_basic", token);
  localStorage.setItem("auth_user", username);
};

export const apiUrl = (path: string) => `${API_BASE}${path}`;

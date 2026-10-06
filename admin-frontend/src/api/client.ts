import axios from "axios";

// Different key from the public site's "ticketing:accessToken" - and since this app runs on its own
// origin, the two stores are physically separate anyway. The distinct name just keeps it obvious.
const ACCESS_TOKEN_KEY = "ticketing-admin:accessToken";

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api",
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function setAccessToken(token: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
}

export function clearAccessToken(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
}

// Admin sessions are short (15 min, no refresh) - when one lapses, any call comes back 401. Clear
// the dead token and tell the AuthContext so the user lands on the login page instead of seeing
// every screen quietly fail.
export const AUTH_EXPIRED_EVENT = "admin-auth-expired";

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401 && getAccessToken()) {
      clearAccessToken();
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }
    return Promise.reject(error);
  },
);

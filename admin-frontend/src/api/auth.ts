import { apiClient } from "./client";
import type { AdminLoginResponse, AdminUser } from "../types";

export function adminLogin(payload: { email: string; password: string }) {
  return apiClient.post<AdminLoginResponse>("/auth/admin/login", payload).then((res) => res.data);
}

export function getMe() {
  return apiClient.get<AdminUser>("/auth/me").then((res) => res.data);
}

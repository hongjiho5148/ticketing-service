import { apiClient } from "./client";
import type { LoginResponse, User } from "../types";

export function signup(payload: { email: string; password: string; name: string }) {
  return apiClient.post<User>("/auth/signup", payload).then((res) => res.data);
}

export function login(payload: { email: string; password: string }) {
  return apiClient.post<LoginResponse>("/auth/login", payload).then((res) => res.data);
}

export function getMe() {
  return apiClient.get<User>("/auth/me").then((res) => res.data);
}

export function updateProfile(name: string) {
  return apiClient.patch<User>("/auth/me", { name }).then((res) => res.data);
}

export function changePassword(currentPassword: string, newPassword: string) {
  return apiClient.patch("/auth/password", { currentPassword, newPassword }).then(() => undefined);
}

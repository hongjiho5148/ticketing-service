import { apiClient } from "./client";
import type { LoginResponse, User } from "../types";

export function signup(payload: { email: string; password: string; name: string }) {
  return apiClient.post<User>("/auth/signup", payload).then((res) => res.data);
}

// The captcha token travels as a header so the request body stays the same shape with or without captcha.
export function login(payload: { email: string; password: string }, captchaToken?: string) {
  return apiClient
    .post<LoginResponse>("/auth/login", payload, captchaToken ? { headers: { "X-Captcha-Token": captchaToken } } : undefined)
    .then((res) => res.data);
}

// Always succeeds from the caller's point of view - the server doesn't reveal whether the address is registered.
export function resendVerification(email: string) {
  return apiClient.post("/auth/resend-verification", { email }).then(() => undefined);
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

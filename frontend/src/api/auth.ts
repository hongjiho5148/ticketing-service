import { apiClient } from "./client";
import type { LoginResponse, User } from "../types";

export function signup(payload: { email: string; password: string; name: string }) {
  return apiClient.post<User>("/auth/signup", payload).then((res) => res.data);
}

// 캡차 토큰은 헤더로 보낸다. 그래야 캡차가 있든 없든 요청 본문의 모양이 같다.
export function login(payload: { email: string; password: string }, captchaToken?: string) {
  return apiClient
    .post<LoginResponse>("/auth/login", payload, captchaToken ? { headers: { "X-Captcha-Token": captchaToken } } : undefined)
    .then((res) => res.data);
}

// 호출한 쪽에서 보면 항상 성공한다. 서버는 그 주소가 가입되어 있는지 알려 주지 않는다.
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

import axios from "axios";

// 공개 사이트의 "ticketing:accessToken"과 키를 다르게 둔다. 이 앱은 자기 오리진에서 돌아서 어차피 두 저장소는
// 물리적으로 분리되어 있다. 이름을 따로 두는 것은 그 점을 분명히 하기 위해서다.
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

// 관리자 세션은 짧고(15분, 갱신 없음) 만료되면 모든 호출이 401로 돌아온다. 죽은 토큰을 지우고 AuthContext에 알려서
// 사용자가 모든 화면이 조용히 실패하는 것을 보는 대신 로그인 페이지로 가게 한다.
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

const BACKEND_ORIGIN = import.meta.env.VITE_BACKEND_ORIGIN ?? "http://localhost:8080";

function startOAuthLogin(provider: "google" | "kakao") {
  window.location.href = `${BACKEND_ORIGIN}/oauth2/authorization/${provider}`;
}

export function SocialLoginButtons() {
  return (
    <div className="social-login">
      <button type="button" className="social-button social-google" onClick={() => startOAuthLogin("google")}>
        Google로 계속하기
      </button>
      <button type="button" className="social-button social-kakao" onClick={() => startOAuthLogin("kakao")}>
        카카오로 계속하기
      </button>
    </div>
  );
}

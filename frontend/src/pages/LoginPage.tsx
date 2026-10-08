import { isAxiosError } from "axios";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { AuthAside } from "../components/AuthAside";
import { ReCaptcha } from "../components/ReCaptcha";
import { resendVerification } from "../api/auth";
import { SocialLoginButtons } from "../components/SocialLoginButtons";
import { useAuth } from "../context/AuthContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import { isCaptchaEnabled } from "../utils/recaptcha";
import type { ApiErrorBody } from "../types";

export function LoginPage() {
  useDocumentTitle("로그인");
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  // Set when the server says the email isn't verified yet - the lost-mail case, where the user needs a way to get a new link.
  const [needsVerification, setNeedsVerification] = useState(false);
  const [resendState, setResendState] = useState<"idle" | "sending" | "sent">("idle");
  const [captchaToken, setCaptchaToken] = useState<string | null>(null);
  // Bumped to get a fresh, unsolved widget: a token is good for one attempt, successful or not.
  const [captchaKey, setCaptchaKey] = useState(0);

  const notice =
    searchParams.get("verified") === "true"
      ? "이메일 인증이 완료됐습니다. 로그인해주세요."
      : searchParams.get("verified") === "false"
        ? "인증 링크가 유효하지 않거나 만료됐습니다."
        : searchParams.get("error") === "oauth2"
          ? "소셜 로그인에 실패했습니다. 다시 시도해주세요."
          : null;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNeedsVerification(false);
    setResendState("idle");
    setIsSubmitting(true);
    try {
      await login(email, password, captchaToken ?? undefined);
      navigate("/");
    } catch (err) {
      setError(extractErrorMessage(err));
      setNeedsVerification(isAxiosError<ApiErrorBody>(err) && err.response?.data?.code === "EMAIL_NOT_VERIFIED");
      if (isCaptchaEnabled) {
        setCaptchaToken(null);
        setCaptchaKey((k) => k + 1);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleResend() {
    setResendState("sending");
    try {
      await resendVerification(email);
      setResendState("sent");
    } catch (err) {
      setError(extractErrorMessage(err));
      setResendState("idle");
    }
  }

  return (
    <div className="auth-layout">
      <AuthAside />
      <div className="form-page">
      <h1>로그인</h1>
      {notice && <p className="form-notice">{notice}</p>}
      <form onSubmit={handleSubmit} className="form">
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {isCaptchaEnabled && <ReCaptcha key={captchaKey} onChange={setCaptchaToken} />}
        {error && <p className="form-error">{error}</p>}
        {needsVerification && (
          <div className="form-notice">
            {resendState === "sent" ? (
              "인증 메일을 다시 보냈어요. 잠시 후 메일함(스팸함 포함)을 확인해주세요."
            ) : (
              <button type="button" className="btn-secondary" onClick={handleResend} disabled={resendState === "sending"}>
                {resendState === "sending" ? "보내는 중..." : "인증 메일 다시 받기"}
              </button>
            )}
          </div>
        )}
        <button type="submit" disabled={isSubmitting || (isCaptchaEnabled && !captchaToken)}>
          {isSubmitting ? "로그인 중..." : "로그인"}
        </button>
      </form>
      <div className="form-divider">또는</div>
      <SocialLoginButtons />
      <p>
        계정이 없으신가요? <Link to="/signup">회원가입</Link>
      </p>
      </div>
    </div>
  );
}

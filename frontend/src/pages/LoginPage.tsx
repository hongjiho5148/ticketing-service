import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { SocialLoginButtons } from "../components/SocialLoginButtons";
import { useAuth } from "../context/AuthContext";
import { extractErrorMessage } from "../utils/error";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

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
    setIsSubmitting(true);
    try {
      await login(email, password);
      navigate("/");
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
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
        {error && <p className="form-error">{error}</p>}
        <button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "로그인 중..." : "로그인"}
        </button>
      </form>
      <div className="form-divider">또는</div>
      <SocialLoginButtons />
      <p>
        계정이 없으신가요? <Link to="/signup">회원가입</Link>
      </p>
    </div>
  );
}

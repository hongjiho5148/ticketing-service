import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { SocialLoginButtons } from "../components/SocialLoginButtons";
import { useAuth } from "../context/AuthContext";
import { extractErrorMessage } from "../utils/error";

export function SignupPage() {
  const { signup } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isDone, setIsDone] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (password !== confirmPassword) {
      setError("비밀번호가 일치하지 않습니다.");
      return;
    }

    setIsSubmitting(true);
    try {
      await signup(email, password, name);
      setIsDone(true);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  }

  if (isDone) {
    return (
      <div className="form-page">
        <h1>회원가입</h1>
        <p className="form-notice">
          <strong>{email}</strong> 으로 인증 메일을 보냈어요. 메일함에서 링크를 눌러 인증을 완료한 뒤 로그인해주세요.
        </p>
        <p>
          <Link to="/login">로그인 하러 가기</Link>
        </p>
      </div>
    );
  }

  return (
    <div className="form-page">
      <h1>회원가입</h1>
      <form onSubmit={handleSubmit} className="form">
        <label>
          이름
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호 (8자 이상)
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            required
          />
        </label>
        <label>
          비밀번호 확인
          <input
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            minLength={8}
            required
          />
        </label>
        {error && <p className="form-error">{error}</p>}
        <button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "가입 중..." : "회원가입"}
        </button>
      </form>
      <div className="form-divider">또는</div>
      <SocialLoginButtons />
      <p>
        이미 계정이 있으신가요? <Link to="/login">로그인</Link>
      </p>
    </div>
  );
}

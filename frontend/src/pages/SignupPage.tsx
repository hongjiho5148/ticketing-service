import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { SocialLoginButtons } from "../components/SocialLoginButtons";
import { useAuth } from "../context/AuthContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import { getPasswordStrength } from "../utils/passwordStrength";

const EMAIL_DOMAINS = ["naver.com", "gmail.com", "daum.net", "kakao.com", "nate.com", "hanmail.net"];
const CUSTOM_DOMAIN = "custom";

export function SignupPage() {
  useDocumentTitle("회원가입");
  const { signup } = useAuth();
  const [emailLocal, setEmailLocal] = useState("");
  const [emailDomain, setEmailDomain] = useState("");
  const [domainPreset, setDomainPreset] = useState(CUSTOM_DOMAIN);
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isDone, setIsDone] = useState(false);

  const email = emailLocal && emailDomain ? `${emailLocal}@${emailDomain}` : "";
  const passwordStrength = getPasswordStrength(password);

  function handleDomainPresetChange(value: string) {
    setDomainPreset(value);
    if (value !== CUSTOM_DOMAIN) {
      setEmailDomain(value);
    } else {
      setEmailDomain("");
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!passwordStrength.isValid) {
      setError("비밀번호는 영문 대/소문자, 숫자, 특수문자를 모두 포함해 8자 이상으로 입력해주세요.");
      return;
    }

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
          <strong>{email}</strong> 으로 인증 메일을 보내고 있어요. 곧 도착하는 메일의 링크를 눌러 인증을 완료한 뒤 로그인해주세요. 메일이 오지 않으면 로그인 화면에서 다시 받을 수 있어요.
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
          <div className="email-field">
            <div className="email-field-row">
              <input
                type="text"
                value={emailLocal}
                onChange={(e) => setEmailLocal(e.target.value)}
                placeholder="이메일"
                required
              />
              <span className="email-at">@</span>
              <input
                type="text"
                value={emailDomain}
                onChange={(e) => {
                  setEmailDomain(e.target.value);
                  setDomainPreset(CUSTOM_DOMAIN);
                }}
                placeholder="도메인 입력"
                required
              />
            </div>
            <select value={domainPreset} onChange={(e) => handleDomainPresetChange(e.target.value)}>
              <option value={CUSTOM_DOMAIN}>직접 입력</option>
              {EMAIL_DOMAINS.map((domain) => (
                <option key={domain} value={domain}>
                  {domain}
                </option>
              ))}
            </select>
          </div>
        </label>
        <label>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            required
          />
        </label>
        {password.length > 0 && (
          <div className="password-strength">
            <div className={`password-strength-bar level-${passwordStrength.level}`}>
              <div className="password-strength-bar-fill" style={{ width: `${(passwordStrength.metCount / 5) * 100}%` }} />
            </div>
            <p className={`password-strength-label level-${passwordStrength.level}`}>보안 상태: {passwordStrength.label}</p>
            <ul className="password-requirements">
              {passwordStrength.requirements.map((req) => (
                <li key={req.key} className={req.met ? "met" : ""}>
                  {req.met ? "✓" : "·"} {req.label}
                </li>
              ))}
            </ul>
          </div>
        )}
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

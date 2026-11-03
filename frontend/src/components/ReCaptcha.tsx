import { useEffect, useRef, useState } from "react";
import { RECAPTCHA_SITE_KEY, loadRecaptcha } from "../utils/recaptcha";

interface ReCaptchaProps {
  /** 사용자가 풀면 토큰이고, 만료되거나 오류가 나면 null이다. 토큰은 한 번만 쓸 수 있다. */
  onChange: (token: string | null) => void;
}

/**
 * "로봇이 아닙니다" 체크박스다. 토큰은 서버 검사 한 번에만 쓸 수 있으므로, 쓸 때마다 부모가 이것을 다시
 * 마운트해서(`key`를 바꿔서) 아직 풀지 않은 새 위젯을 얻는다.
 */
export function ReCaptcha({ onChange }: ReCaptchaProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadRecaptcha()
      .then((grecaptcha) => {
        if (cancelled || !containerRef.current) return;
        grecaptcha.render(containerRef.current, {
          sitekey: RECAPTCHA_SITE_KEY,
          callback: (token) => onChangeRef.current(token),
          "expired-callback": () => onChangeRef.current(null),
          "error-callback": () => onChangeRef.current(null),
        });
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loadFailed) {
    return <p className="form-error">로봇 확인을 불러오지 못했어요. 광고 차단 프로그램을 끄고 새로고침해주세요.</p>;
  }
  return <div ref={containerRef} className="captcha-box" />;
}

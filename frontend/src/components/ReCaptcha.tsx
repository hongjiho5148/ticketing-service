import { useEffect, useRef, useState } from "react";
import { RECAPTCHA_SITE_KEY, loadRecaptcha } from "../utils/recaptcha";

interface ReCaptchaProps {
  /** A token when the user solves it; null when it expires or errors. Tokens are single-use. */
  onChange: (token: string | null) => void;
}

/**
 * The "I'm not a robot" checkbox. A token is good for one server check, so after every use the
 * parent remounts this (by changing its `key`) to get a fresh, unsolved widget.
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

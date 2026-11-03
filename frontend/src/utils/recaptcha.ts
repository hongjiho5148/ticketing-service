// 이 앱이 쓰는 Google reCAPTCHA v2 API 부분에 대한 최소한의 타입이다.
interface Grecaptcha {
  ready: (callback: () => void) => void;
  render: (
    container: HTMLElement,
    options: {
      sitekey: string;
      callback: (token: string) => void;
      "expired-callback": () => void;
      "error-callback": () => void;
    },
  ) => number;
}

declare global {
  interface Window {
    grecaptcha?: Grecaptcha;
  }
}

// 사이트 키는 원래 공개다. 설정된 것이 없으면 위젯을 아예 건너뛰는데, 서버와 같은 동작이다(시크릿 키가 없으면
// 검사도 없다). 그래서 캡차 없이도 앱이 돈다.
export const RECAPTCHA_SITE_KEY = (import.meta.env.VITE_RECAPTCHA_SITE_KEY as string | undefined) ?? "";
export const isCaptchaEnabled = RECAPTCHA_SITE_KEY !== "";

const SCRIPT_URL = "https://www.google.com/recaptcha/api.js?render=explicit";

let loading: Promise<Grecaptcha> | null = null;

/** Google의 스크립트를 한 번만 불러오고 grecaptcha가 위젯을 그릴 준비가 되면 resolve한다. */
export function loadRecaptcha(): Promise<Grecaptcha> {
  if (loading) return loading;
  loading = new Promise<Grecaptcha>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = SCRIPT_URL;
    script.async = true;
    script.defer = true;
    script.onload = () => {
      const grecaptcha = window.grecaptcha;
      if (!grecaptcha) {
        reject(new Error("grecaptcha missing after load"));
        return;
      }
      grecaptcha.ready(() => resolve(grecaptcha));
    };
    script.onerror = () => reject(new Error("Failed to load reCAPTCHA"));
    document.head.appendChild(script);
  }).catch((err) => {
    // 실패를 영원히 캐시하지 않고 나중의 시도가 재시도할 수 있게 한다(광고 차단기, 불안정한 네트워크).
    loading = null;
    document.querySelectorAll(`script[src="${SCRIPT_URL}"]`).forEach((el) => el.remove());
    throw err;
  });
  return loading;
}

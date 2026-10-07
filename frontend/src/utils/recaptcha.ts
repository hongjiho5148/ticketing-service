// Minimal typing for the bits of Google's reCAPTCHA v2 API this app uses.
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

// The site key is public by design. With none configured the widget is skipped entirely, which
// matches the server (no secret key = no check), so the app still runs without captcha.
export const RECAPTCHA_SITE_KEY = (import.meta.env.VITE_RECAPTCHA_SITE_KEY as string | undefined) ?? "";
export const isCaptchaEnabled = RECAPTCHA_SITE_KEY !== "";

const SCRIPT_URL = "https://www.google.com/recaptcha/api.js?render=explicit";

let loading: Promise<Grecaptcha> | null = null;

/** Loads Google's script once and resolves when grecaptcha is ready to render widgets. */
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
    // Let a later attempt retry instead of caching the failure forever (ad blockers, flaky network).
    loading = null;
    document.querySelectorAll(`script[src="${SCRIPT_URL}"]`).forEach((el) => el.remove());
    throw err;
  });
  return loading;
}

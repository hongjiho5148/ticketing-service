import PortOne from "@portone/browser-sdk/v2";

const STORE_ID = import.meta.env.VITE_PORTONE_STORE_ID;

export type PayMethod = "CARD" | "KAKAOPAY" | "NAVERPAY";

interface PayMethodConfig {
  label: string;
  // Each method is its own channel in the PortOne console, so each has its own key.
  channelKey: string | undefined;
}

const PAY_METHODS: Record<PayMethod, PayMethodConfig> = {
  CARD: { label: "카드", channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY },
  KAKAOPAY: { label: "카카오페이", channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY_KAKAOPAY },
  NAVERPAY: { label: "네이버페이", channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY_NAVERPAY },
};

/** Only methods whose channel key is configured are offered - a button with no channel behind it can only fail. */
export function availablePayMethods(): { method: PayMethod; label: string }[] {
  return (Object.keys(PAY_METHODS) as PayMethod[])
    .filter((method) => Boolean(PAY_METHODS[method].channelKey))
    .map((method) => ({ method, label: PAY_METHODS[method].label }));
}

export interface PaymentRequest {
  orderName: string;
  totalAmount: number;
  customerName: string;
  customerEmail: string | null;
  method: PayMethod;
}

export interface PaymentResult {
  paymentId: string;
  failed: boolean;
  failureMessage?: string;
}

export async function requestPayment(request: PaymentRequest): Promise<PaymentResult> {
  const paymentId = crypto.randomUUID();
  const config = PAY_METHODS[request.method];
  if (!config.channelKey) {
    return { paymentId, failed: true, failureMessage: `${config.label} 결제 채널이 설정되지 않았어요.` };
  }

  const common = {
    storeId: STORE_ID,
    channelKey: config.channelKey,
    paymentId,
    orderName: request.orderName,
    totalAmount: request.totalAmount,
    currency: "KRW" as const,
    customer: {
      fullName: request.customerName,
      // Kakao logins never give us an email (no email scope) - only send it when we actually have one.
      ...(request.customerEmail ? { email: request.customerEmail } : {}),
    },
  };

  const response = await PortOne.requestPayment(
    request.method === "CARD"
      ? { ...common, payMethod: "CARD" }
      : { ...common, payMethod: "EASY_PAY", easyPay: { easyPayProvider: request.method } },
  );

  if (!response || response.code) {
    return { paymentId, failed: true, failureMessage: response?.message ?? "결제창을 여는 데 실패했습니다." };
  }
  return { paymentId: response.paymentId, failed: false };
}

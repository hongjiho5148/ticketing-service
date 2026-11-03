import PortOne from "@portone/browser-sdk/v2";

const STORE_ID = import.meta.env.VITE_PORTONE_STORE_ID;

export type PayMethod = "CARD" | "KAKAOPAY" | "NAVERPAY";

interface PayMethodConfig {
  label: string;
  // 결제 수단마다 PortOne 콘솔에서 자기 채널이므로 각자 자기 키를 가진다.
  channelKey: string | undefined;
}

const PAY_METHODS: Record<PayMethod, PayMethodConfig> = {
  CARD: { label: "카드", channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY },
  KAKAOPAY: { label: "카카오페이", channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY_KAKAOPAY },
  NAVERPAY: { label: "네이버페이", channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY_NAVERPAY },
};

/** 채널 키가 설정된 수단만 보여 준다. 뒤에 채널이 없는 버튼은 실패할 수밖에 없다. */
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

export async function requestPayment(request: PaymentRequest, paymentId: string): Promise<PaymentResult> {
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
      // 카카오 로그인은 이메일을 주지 않으므로(이메일 스코프 없음) 실제로 가지고 있을 때만 보낸다.
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

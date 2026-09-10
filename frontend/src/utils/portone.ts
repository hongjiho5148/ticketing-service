import PortOne from "@portone/browser-sdk/v2";

const STORE_ID = import.meta.env.VITE_PORTONE_STORE_ID;
const CHANNEL_KEY = import.meta.env.VITE_PORTONE_CHANNEL_KEY;

export interface CardPaymentRequest {
  orderName: string;
  totalAmount: number;
  customerName: string;
  customerEmail: string | null;
}

export interface CardPaymentResult {
  paymentId: string;
  failed: boolean;
  failureMessage?: string;
}

export async function requestCardPayment(request: CardPaymentRequest): Promise<CardPaymentResult> {
  const paymentId = crypto.randomUUID();

  const response = await PortOne.requestPayment({
    storeId: STORE_ID,
    channelKey: CHANNEL_KEY,
    paymentId,
    orderName: request.orderName,
    totalAmount: request.totalAmount,
    currency: "KRW",
    payMethod: "CARD",
    customer: {
      fullName: request.customerName,
      // Kakao logins never give us an email (no email scope) - only send it when we actually have one.
      ...(request.customerEmail ? { email: request.customerEmail } : {}),
    },
  });

  if (!response || response.code) {
    return { paymentId, failed: true, failureMessage: response?.message ?? "결제창을 여는 데 실패했습니다." };
  }
  return { paymentId: response.paymentId, failed: false };
}

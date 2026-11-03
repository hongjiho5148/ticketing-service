export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";
export type EventCategory = "CONCERT" | "MUSICAL" | "PLAY" | "CLASSIC" | "SPORTS" | "EXHIBITION" | "FESTIVAL" | "ETC";
export type SeatStatus = "AVAILABLE" | "HOLD" | "SOLD";
export type ReservationStatus = "HOLDING" | "CONFIRMED" | "CANCELLED" | "EXPIRED";
export type OrderStatus = "PENDING" | "PAID" | "FAILED" | "CANCELLED" | "PARTIALLY_REFUNDED";
export type PaymentStatus = "SUCCESS" | "FAILED";

export type AuthProvider = "LOCAL" | "GOOGLE" | "KAKAO";
export type Role = "USER" | "ADMIN";

export interface User {
  id: number;
  // 카카오 로그인은 이메일 스코프를 주지 않으므로 이 값은 실제로 null일 수 있다.
  email: string | null;
  name: string;
  provider: AuthProvider;
  role: Role;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface EventSummary {
  id: number;
  title: string;
  venue: string;
  category: EventCategory;
  // 첫 후기가 작성되기 전까지는 null이다.
  averageRating: number | null;
  reviewCount: number;
  startAt: string;
  openAt: string;
  /** 좌석을 잡을 수 있는 마지막 순간이다. 공연 시작 두 시간 전이다. */
  salesCloseAt: string;
  status: EventStatus;
}

export interface EventListResponse {
  content: EventSummary[];
  totalElements: number;
}

export interface SeatGradeSummary {
  grade: string;
  totalCount: number;
  availableCount: number;
}

export interface SeatSectionSummary {
  section: string;
  grade: string;
  price: number;
  totalCount: number;
  availableCount: number;
}

export interface EventDetail {
  id: number;
  title: string;
  venue: string;
  category: EventCategory;
  averageRating: number | null;
  reviewCount: number;
  description: string | null;
  startAt: string;
  openAt: string;
  salesCloseAt: string;
  status: EventStatus;
  sourceUrl: string | null;
  seatSummary: SeatGradeSummary[];
  sectionSummary: SeatSectionSummary[];
}

export interface Seat {
  id: number;
  seatNo: string;
  grade: string;
  section: string;
  rowNo: number;
  seatNumber: number;
  price: number;
  status: SeatStatus;
}

export interface Reservation {
  reservationId: number;
  seatId: number;
  status: ReservationStatus;
  holdExpireAt: string;
}

export interface Order {
  orderId: number;
  reservationId: number;
  // totalPrice는 실제로 청구되는 금액이다. originalPrice - discountAmount - pointsUsed.
  originalPrice: number;
  couponCode: string | null;
  discountAmount: number;
  pointsUsed: number;
  totalPrice: number;
  status: OrderStatus;
}

export type PointTransactionType = "EARN" | "USE" | "RESTORE" | "CLAWBACK";

export interface PointEntry {
  delta: number;
  type: PointTransactionType;
  orderId: number;
  createdAt: string;
}

export interface PointSummary {
  balance: number;
  transactions: PointEntry[];
}

export interface CouponHistoryItem {
  code: string;
  discountAmount: number;
  orderId: number;
  redeemedAt: string;
}

// 로그인한 사용자가 아직 적용할 수 있는 쿠폰이다. 유효 기간 안이고, 남은 횟수가 있고, 그 사용자가 아직 쓰지 않았다.
export interface AvailableCoupon {
  code: string;
  discountType: "FLAT" | "PERCENT";
  discountValue: number;
  validTo: string;
}

export interface PaymentResult {
  orderId: number;
  paymentStatus: PaymentStatus;
  paidAt: string;
  // 서버가 결제를 직접 되돌렸을 때 설정한다. 예: "HOLD_EXPIRED"(결제하는 동안 홀드가 끝났다. 환불됨).
  failureReason?: string | null;
}

export interface OrderHistoryItem {
  orderId: number;
  eventId: number;
  eventTitle: string;
  venue: string;
  grade: string;
  section: string;
  rowNo: number;
  seatNumber: number;
  seatNo: string;
  totalPrice: number;
  // 실제로 어떻게 결제했는지("CARD", "KAKAOPAY"...)와 취소 시 돌려준 금액이다. 결제 전에는 null이다.
  paymentMethod: string | null;
  refundedAmount: number | null;
  status: OrderStatus;
  // 양도가 받는 사람을 기다리면 "PENDING", 수락되면 "TRANSFERRED".
  transferStatus: "PENDING" | "TRANSFERRED" | null;
  transferable: boolean;
  // 좌석이 아직 잡혀 있는 PENDING 주문에만 설정한다. 그때까지는 결제를 이어서 할 수 있다.
  holdExpireAt: string | null;
  createdAt: string;
}

export interface Checkout {
  order: Order;
  seatId: number;
  eventId: number;
  holdExpireAt: string;
}

export interface OrderHistoryListResponse {
  content: OrderHistoryItem[];
  totalElements: number;
}

export type TicketStatus = "ISSUED" | "USED";

export interface Ticket {
  ticketId: number;
  qrToken: string;
  status: TicketStatus;
  issuedAt: string;
}

export interface TicketHistoryItem {
  ticketId: number;
  orderId: number;
  eventId: number;
  eventTitle: string;
  venue: string;
  grade: string;
  section: string;
  rowNo: number;
  seatNumber: number;
  seatNo: string;
  eventStartAt: string;
  status: TicketStatus;
  issuedAt: string;
}

export interface Review {
  id: number;
  rating: number;
  content: string;
  createdAt: string;
}

export interface ReviewListResponse {
  content: Review[];
  totalElements: number;
  averageRating: number | null;
}

export interface NotificationPreference {
  emailOptIn: boolean;
}

export type QueueStatus = "WAITING" | "PASSED";

export interface QueueEnterResult {
  queueToken: string;
  rankNo: number;
  estimatedWaitSeconds: number;
}

export interface QueueStatusResult {
  rankNo: number;
  status: QueueStatus;
  estimatedWaitSeconds: number | null;
  passToken: string | null;
  /** 입장한 사람에게는 이 응답 시점에 입장권이 얼마나 남았는지를 지금부터의 초로 담는다. */
  passExpiresInSeconds: number | null;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  timestamp: string;
}

export type TransferStatus = "PENDING" | "ACCEPTED" | "DECLINED" | "CANCELLED" | "EXPIRED";

export interface TransferItem {
  transferId: number;
  orderId: number;
  direction: "INCOMING" | "OUTGOING";
  status: TransferStatus;
  // 받은 양도는 보낸 사람의 이름, 보낸 양도는 마스킹한 받는 사람의 이메일이다.
  counterpart: string;
  eventTitle: string;
  venue: string;
  eventStartAt: string;
  grade: string;
  section: string;
  seatNo: string;
  createdAt: string;
}

export interface AlertStatus {
  subscribed: boolean;
}

export interface RefundPreview {
  cancellable: boolean;
  refundPercent: number;
  refundAmount: number;
  feeAmount: number;
  pointsRestored: number;
  // 쿠폰은 전액 환불일 때만 돌아온다. 부분 환불이면 쓴 것으로 남는다.
  couponRestored: boolean;
  couponForfeited: boolean;
}

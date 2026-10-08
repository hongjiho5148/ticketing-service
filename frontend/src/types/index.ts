export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";
export type EventCategory = "CONCERT" | "MUSICAL" | "SPORTS" | "EXHIBITION" | "FESTIVAL" | "ETC";
export type SeatStatus = "AVAILABLE" | "HOLD" | "SOLD";
export type ReservationStatus = "HOLDING" | "CONFIRMED" | "CANCELLED" | "EXPIRED";
export type OrderStatus = "PENDING" | "PAID" | "FAILED" | "CANCELLED" | "PARTIALLY_REFUNDED";
export type PaymentStatus = "SUCCESS" | "FAILED";

export type AuthProvider = "LOCAL" | "GOOGLE" | "KAKAO";
export type Role = "USER" | "ADMIN";

export interface User {
  id: number;
  // Kakao logins don't grant an email scope, so this can genuinely be null.
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
  // null until the first review is written.
  averageRating: number | null;
  reviewCount: number;
  startAt: string;
  openAt: string;
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
  status: EventStatus;
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
  // totalPrice is what's actually charged: originalPrice - discountAmount - pointsUsed.
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

// A coupon the signed-in user can still apply: in its validity window, uses left, not yet redeemed by them.
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
  // How it was actually paid ("CARD", "KAKAOPAY"...) and what was given back on cancellation; null before payment.
  paymentMethod: string | null;
  refundedAmount: number | null;
  status: OrderStatus;
  // "PENDING" while a transfer awaits the recipient, "TRANSFERRED" once accepted.
  transferStatus: "PENDING" | "TRANSFERRED" | null;
  transferable: boolean;
  createdAt: string;
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
  // The sender's name for incoming transfers, the masked recipient email for outgoing ones.
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
  // A coupon comes back only with a full refund; with a partial refund it stays spent.
  couponRestored: boolean;
  couponForfeited: boolean;
}

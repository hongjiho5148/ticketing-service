export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";
export type SeatStatus = "AVAILABLE" | "HOLD" | "SOLD";
export type ReservationStatus = "HOLDING" | "CONFIRMED" | "CANCELLED" | "EXPIRED";
export type OrderStatus = "PENDING" | "PAID" | "FAILED" | "CANCELLED";
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
  status: OrderStatus;
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

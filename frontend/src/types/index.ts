export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";
export type SeatStatus = "AVAILABLE" | "HOLD" | "SOLD";
export type ReservationStatus = "HOLDING" | "CONFIRMED" | "CANCELLED" | "EXPIRED";
export type OrderStatus = "PENDING" | "PAID" | "FAILED" | "CANCELLED";
export type PaymentStatus = "SUCCESS" | "FAILED";

export interface User {
  id: number;
  email: string;
  name: string;
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

export interface EventDetail {
  id: number;
  title: string;
  venue: string;
  startAt: string;
  status: EventStatus;
  seatSummary: SeatGradeSummary[];
}

export interface Seat {
  id: number;
  seatNo: string;
  grade: string;
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
  totalPrice: number;
  status: OrderStatus;
}

export interface PaymentResult {
  orderId: number;
  paymentStatus: PaymentStatus;
  paidAt: string;
}

export interface OrderHistoryItem {
  orderId: number;
  eventTitle: string;
  seatNo: string;
  totalPrice: number;
  status: OrderStatus;
  createdAt: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  timestamp: string;
}

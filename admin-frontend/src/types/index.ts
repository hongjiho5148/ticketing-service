export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";
export type EventCategory = "CONCERT" | "MUSICAL" | "PLAY" | "CLASSIC" | "SPORTS" | "EXHIBITION" | "FESTIVAL" | "ETC";

export type KopisGenre = "PLAY" | "MUSICAL" | "CLASSIC" | "KOREAN_MUSIC" | "POPULAR_MUSIC" | "DANCE";

export interface KopisImportPayload {
  genre: KopisGenre;
  limit: number;
}

export interface KopisImportResult {
  created: number;
  alreadyImported: number;
  notUpcoming: number;
  failed: number;
  createdTitles: string[];
}
export type Role = "USER" | "ADMIN";

export interface AdminUser {
  id: number;
  email: string | null;
  name: string;
  role: Role;
}

export interface AdminLoginResponse {
  accessToken: string;
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

export interface EventDetail {
  id: number;
  title: string;
  venue: string;
  description: string | null;
  category: EventCategory;
  startAt: string;
  openAt: string;
  status: EventStatus;
}

export interface EventUpsertPayload {
  title: string;
  venue: string;
  description: string;
  category: EventCategory;
  startAt: string;
  openAt: string;
  status: EventStatus;
}

export interface SeatBlockPayload {
  section: string;
  grade: string;
  price: number;
  rows: number;
  seatsPerRow: number;
}

export interface GradeStats {
  grade: string;
  total: number;
  available: number;
  hold: number;
  sold: number;
}

export interface EventStats {
  eventId: number;
  title: string;
  totalSeats: number;
  availableSeats: number;
  holdSeats: number;
  soldSeats: number;
  soldSeatRevenue: number;
  grades: GradeStats[];
}

export type AdminOrderStatus = "PENDING" | "PAID" | "FAILED" | "CANCELLED" | "PARTIALLY_REFUNDED";

export interface AdminOrder {
  orderId: number;
  eventId: number;
  eventTitle: string | null;
  grade: string | null;
  section: string | null;
  seatNo: string | null;
  totalPrice: number;
  refundedAmount: number | null;
  status: AdminOrderStatus;
  paymentMethod: string | null;
  buyerId: number;
  buyerName: string | null;
  buyerEmail: string | null;
  createdAt: string;
}

export interface AdminOrderList {
  content: AdminOrder[];
  totalElements: number;
}

export interface OrderSummary {
  eventId: number;
  paidCount: number;
  cancelledCount: number;
  revenue: number;
}

export interface ScanResult {
  ticketId: number;
  orderId: number;
  seatId: number;
  usedAt: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  timestamp: string;
}

export type DiscountType = "FLAT" | "PERCENT";

export interface Coupon {
  id: number;
  code: string;
  discountType: DiscountType;
  discountValue: number;
  validFrom: string;
  validTo: string;
  maxUses: number;
  usedCount: number;
}

export interface CouponCreatePayload {
  code: string;
  discountType: DiscountType;
  discountValue: number;
  validFrom: string;
  validTo: string;
  maxUses: number;
}

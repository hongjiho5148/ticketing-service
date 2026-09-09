import { apiClient } from "./client";
import type { Reservation } from "../types";

export function createReservation(seatId: number) {
  return apiClient.post<Reservation>("/reservations", { seatId }).then((res) => res.data);
}

export function cancelReservation(reservationId: number) {
  return apiClient.delete(`/reservations/${reservationId}`).then(() => undefined);
}

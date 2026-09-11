import { apiClient } from "./client";
import type { Reservation } from "../types";

export function createReservation(seatId: number, passToken: string) {
  return apiClient
    .post<Reservation>("/reservations", { seatId }, { headers: { "X-Pass-Token": passToken } })
    .then((res) => res.data);
}

export function cancelReservation(reservationId: number) {
  return apiClient.delete(`/reservations/${reservationId}`).then(() => undefined);
}

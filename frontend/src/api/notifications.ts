import { apiClient } from "./client";
import type { NotificationPreference } from "../types";

export function fetchNotificationPreference() {
  return apiClient.get<NotificationPreference>("/auth/notification-prefs").then((res) => res.data);
}

export function updateNotificationPreference(emailOptIn: boolean) {
  return apiClient
    .put<NotificationPreference>("/auth/notification-prefs", { emailOptIn })
    .then((res) => res.data);
}

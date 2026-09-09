import { isAxiosError } from "axios";
import type { ApiErrorBody } from "../types";

export function extractErrorMessage(error: unknown): string {
  if (isAxiosError<ApiErrorBody>(error) && error.response?.data?.message) {
    return error.response.data.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "알 수 없는 오류가 발생했습니다.";
}

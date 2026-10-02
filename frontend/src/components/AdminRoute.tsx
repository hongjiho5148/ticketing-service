import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function AdminRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return <p className="page-status">로딩 중...</p>;
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  if (user.role !== "ADMIN") {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

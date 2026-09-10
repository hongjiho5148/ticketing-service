import { useEffect, useState } from "react";
import { Navigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function OAuth2RedirectPage() {
  const { completeOAuthLogin } = useAuth();
  const [searchParams] = useSearchParams();
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");

  useEffect(() => {
    const accessToken = searchParams.get("accessToken");
    if (!accessToken) {
      setStatus("error");
      return;
    }
    completeOAuthLogin(accessToken)
      .then(() => setStatus("done"))
      .catch(() => setStatus("error"));
  }, [searchParams, completeOAuthLogin]);

  if (status === "done") {
    return <Navigate to="/" replace />;
  }
  if (status === "error") {
    return <Navigate to="/login?error=oauth2" replace />;
  }
  return <p className="page-status">로그인 처리 중...</p>;
}

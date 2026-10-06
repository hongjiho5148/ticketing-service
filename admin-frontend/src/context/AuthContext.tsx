import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { adminLogin, getMe } from "../api/auth";
import { AUTH_EXPIRED_EVENT, clearAccessToken, getAccessToken, setAccessToken } from "../api/client";
import type { AdminUser } from "../types";

interface AuthContextValue {
  user: AdminUser | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AdminUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      setIsLoading(false);
      return;
    }
    getMe()
      .then((me) => {
        if (me.role === "ADMIN") {
          setUser(me);
        } else {
          clearAccessToken();
        }
      })
      .catch(() => clearAccessToken())
      .finally(() => setIsLoading(false));
  }, []);

  useEffect(() => {
    const handleExpired = () => setUser(null);
    window.addEventListener(AUTH_EXPIRED_EVENT, handleExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleExpired);
  }, []);

  async function login(email: string, password: string) {
    const response = await adminLogin({ email, password });
    setAccessToken(response.accessToken);
    setUser(await getMe());
  }

  function logout() {
    clearAccessToken();
    setUser(null);
  }

  return <AuthContext.Provider value={{ user, isLoading, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}

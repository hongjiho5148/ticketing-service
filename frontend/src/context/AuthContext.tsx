import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { getMe, login as loginApi, signup as signupApi } from "../api/auth";
import { clearAccessToken, getAccessToken, setAccessToken } from "../api/client";
import type { User } from "../types";

interface AuthContextValue {
  user: User | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string, name: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      setIsLoading(false);
      return;
    }
    getMe()
      .then(setUser)
      .catch(() => clearAccessToken())
      .finally(() => setIsLoading(false));
  }, []);

  async function login(email: string, password: string) {
    const response = await loginApi({ email, password });
    setAccessToken(response.accessToken);
    setUser(await getMe());
  }

  async function signup(email: string, password: string, name: string) {
    await signupApi({ email, password, name });
    await login(email, password);
  }

  function logout() {
    clearAccessToken();
    setUser(null);
  }

  return (
    <AuthContext.Provider value={{ user, isLoading, login, signup, logout }}>{children}</AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}

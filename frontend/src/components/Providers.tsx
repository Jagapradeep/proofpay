"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export type Role = "CLIENT" | "FREELANCER";

export interface PublicConfig {
  paypalClientId: string;
  demoClientEmail: string;
  demoFreelancerEmail: string;
}

interface Ctx {
  role: Role;
  setRole: (r: Role) => void;
  config: PublicConfig;
}

const AppContext = createContext<Ctx | null>(null);
const ROLE_KEY = "proofpay.role";

export function Providers({ config, children }: { config: PublicConfig; children: ReactNode }) {
  const [role, setRoleState] = useState<Role>("CLIENT");

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(ROLE_KEY);
      if (saved === "CLIENT" || saved === "FREELANCER") setRoleState(saved);
    } catch {
      // storage blocked: keep the default
    }
  }, []);

  const setRole = (r: Role) => {
    setRoleState(r);
    try {
      window.localStorage.setItem(ROLE_KEY, r);
    } catch {
      // ignore
    }
  };

  return <AppContext.Provider value={{ role, setRole, config }}>{children}</AppContext.Provider>;
}

export function useApp(): Ctx {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp outside Providers");
  return ctx;
}

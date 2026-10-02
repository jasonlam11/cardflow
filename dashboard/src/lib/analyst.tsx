"use client";

/**
 * Who is reviewing. There's no login system (out of scope for the demo); the
 * analyst types their name, it's remembered in this browser, and every
 * decision records it in the audit trail.
 *
 * localStorage is external state, so it's read with useSyncExternalStore
 * (no setState-in-effect, and the server render simply sees "").
 */
import { createContext, useContext, useSyncExternalStore, type ReactNode } from "react";

const KEY = "cardflow.analyst";
const EVENT = "cardflow-analyst-change";

function read(): string {
  try {
    return localStorage.getItem(KEY) ?? "";
  } catch {
    return ""; // storage blocked: fine, just not remembered
  }
}

function subscribe(onChange: () => void) {
  window.addEventListener("storage", onChange); // other tabs
  window.addEventListener(EVENT, onChange); // this tab
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener(EVENT, onChange);
  };
}

function write(name: string) {
  try {
    localStorage.setItem(KEY, name);
  } catch {
    /* ignore */
  }
  window.dispatchEvent(new Event(EVENT));
}

const AnalystContext = createContext<{ analyst: string; setAnalyst: (name: string) => void }>({
  analyst: "",
  setAnalyst: () => {},
});

export function AnalystProvider({ children }: { children: ReactNode }) {
  const analyst = useSyncExternalStore(subscribe, read, () => "");
  return <AnalystContext.Provider value={{ analyst, setAnalyst: write }}>{children}</AnalystContext.Provider>;
}

export const useAnalyst = () => useContext(AnalystContext);

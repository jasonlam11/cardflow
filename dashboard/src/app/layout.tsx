import type { Metadata } from "next";

import { Nav } from "@/components/Nav";

import "./globals.css";
import { Providers } from "./providers";

export const metadata: Metadata = {
  title: "CardFlow ops",
  description: "Transactions, fraud review queue and audit trail for the CardFlow demo",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="min-h-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
        <Providers>
          <Nav />
          <main className="mx-auto max-w-7xl px-4 py-6">{children}</main>
        </Providers>
      </body>
    </html>
  );
}

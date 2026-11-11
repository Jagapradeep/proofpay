import type { Metadata } from "next";
import type { ReactNode } from "react";
import { Header } from "@/components/Header";
import { Providers } from "@/components/Providers";
import "./globals.css";

// Read runtime env on every request (Render sets it at run time, not build time).
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "ProofPay",
  description: "AI escrow that pays on proof. PayPal holds the money; an AI verifier checks the work; code decides.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  const config = {
    paypalClientId: process.env.PAYPAL_CLIENT_ID ?? "",
    demoClientEmail: process.env.DEMO_CLIENT_EMAIL ?? "",
    demoFreelancerEmail: process.env.DEMO_FREELANCER_EMAIL ?? "",
  };
  return (
    // Browser extensions (e.g. QuillBot, Grammarly) add attributes to <html>; don't treat that as an error.
    <html lang="en" suppressHydrationWarning>
      <body className="min-h-screen font-sans antialiased">
        <Providers config={config}>
          <Header />
          <main className="mx-auto max-w-6xl px-4 py-8">{children}</main>
          <footer className="mx-auto max-w-6xl px-4 pb-10 text-xs text-muted">
            ProofPay · PayPal sandbox demo · The LLM proposes, code disposes.
          </footer>
        </Providers>
      </body>
    </html>
  );
}

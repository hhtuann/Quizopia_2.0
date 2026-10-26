import type { Metadata } from "next";
import { Calistoga, JetBrains_Mono, Plus_Jakarta_Sans } from "next/font/google";
import { SkipLink } from "../components/ui/skip-link";
import { AppProviders } from "../lib/providers/app-providers";
import { getServerLocale } from "../lib/i18n/server";
import "katex/dist/katex.min.css";
import "./globals.css";

const jakarta = Plus_Jakarta_Sans({
  subsets: ["latin", "latin-ext", "vietnamese"],
  display: "swap",
  fallback: ["ui-sans-serif", "system-ui", "sans-serif"],
  variable: "--font-plus-jakarta-sans",
});

const calistoga = Calistoga({
  subsets: ["latin", "latin-ext", "vietnamese"],
  display: "swap",
  fallback: ["ui-serif", "Georgia", "serif"],
  variable: "--font-calistoga",
  weight: "400",
});

const jetBrainsMono = JetBrains_Mono({
  subsets: ["latin", "latin-ext", "vietnamese"],
  display: "swap",
  fallback: ["ui-monospace", "SFMono-Regular", "monospace"],
  variable: "--font-jetbrains-mono",
});

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  return {
    title:
      locale === "vi"
        ? "Quizopia 2.0 — Nền tảng học tập và kiểm tra"
        : "Quizopia 2.0 — Learning and assessment platform",
    description:
      locale === "vi"
        ? "Quizopia 2.0: học tập, sáng tạo và kiểm tra trực tuyến."
        : "Quizopia 2.0: learn, create and assess online.",
  };
}

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  // Server-resolved locale keeps the first paint and <html lang> deterministic;
  // the provider only takes over after hydration with the same value.
  const locale = await getServerLocale();
  return (
    <html lang={locale}>
      <body
        className={`${jakarta.variable} ${calistoga.variable} ${jetBrainsMono.variable}`}
      >
        <AppProviders initialLocale={locale}>
          <SkipLink href="#main-content">Skip to main content</SkipLink>
          {children}
        </AppProviders>
      </body>
    </html>
  );
}

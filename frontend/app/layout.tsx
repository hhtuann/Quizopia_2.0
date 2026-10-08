import type { Metadata } from "next";
import { Calistoga, JetBrains_Mono, Plus_Jakarta_Sans } from "next/font/google";
import { SkipLink } from "../components/ui/skip-link";
import { AppProviders } from "../lib/providers/app-providers";
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

export const metadata: Metadata = {
  title: "Quizopia 2.0",
  description: "Quizopia learning and assessment platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${jakarta.variable} ${calistoga.variable} ${jetBrainsMono.variable}`}
      >
        <SkipLink href="#main-content">Skip to main content</SkipLink>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}

import type { Metadata } from "next";
import { Calistoga, Inter, JetBrains_Mono } from "next/font/google";
import { SkipLink } from "../components/ui/skip-link";
import { AppProviders } from "../lib/providers/app-providers";
import "./globals.css";

const inter = Inter({
  subsets: ["latin", "latin-ext", "vietnamese"],
  display: "swap",
  fallback: ["ui-sans-serif", "system-ui", "sans-serif"],
  variable: "--font-inter",
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
        className={`${inter.variable} ${calistoga.variable} ${jetBrainsMono.variable}`}
      >
        <SkipLink href="#main-content">Skip to main content</SkipLink>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}

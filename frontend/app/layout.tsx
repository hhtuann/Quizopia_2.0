import type { Metadata } from "next";
import { Plus_Jakarta_Sans } from "next/font/google";
import { SkipLink } from "../components/ui/skip-link";
import { AppProviders } from "../lib/providers/app-providers";
import "./globals.css";

const plusJakartaSans = Plus_Jakarta_Sans({
  subsets: ["latin", "latin-ext", "vietnamese"],
  display: "swap",
  fallback: ["ui-sans-serif", "system-ui", "sans-serif"],
  variable: "--font-plus-jakarta-sans",
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
      <body className={plusJakartaSans.variable}>
        <SkipLink href="#main-content">Skip to main content</SkipLink>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}

import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Quizopia 2.0",
  description: "Quizopia 2.0 development scaffold",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

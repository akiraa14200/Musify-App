import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "Transcript Studio", description: "Free-first AI audio and video transcription workspace." };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}

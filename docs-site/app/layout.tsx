import type { Metadata, Viewport } from 'next';
import localFont from 'next/font/local';
import { Provider } from '@/components/provider';
import { basePath, siteUrl } from '@/lib/shared';
import './global.css';

const geist = localFont({
  src: [
    { path: './fonts/geist-400.woff2', weight: '400' },
    { path: './fonts/geist-500.woff2', weight: '500' },
    { path: './fonts/geist-600.woff2', weight: '600' },
    { path: './fonts/geist-700.woff2', weight: '700' },
  ],
  variable: '--font-geist',
  display: 'swap',
});

const geistMono = localFont({
  src: [
    { path: './fonts/geist-mono-400.woff2', weight: '400' },
    { path: './fonts/geist-mono-500.woff2', weight: '500' },
  ],
  variable: '--font-geist-mono',
  display: 'swap',
});

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: 'supermux docs',
    template: '%s · supermux docs',
  },
  description:
    'How to install and use supermux, the open-source workspace for coding agents, and how to build on @supermux/core.',
  icons: {
    icon: [
      { url: `${basePath}/icon.svg`, type: 'image/svg+xml' },
      { url: `${basePath}/icon-192.png`, sizes: '192x192', type: 'image/png' },
    ],
    apple: `${basePath}/apple-touch-icon.png`,
  },
  openGraph: {
    siteName: 'supermux docs',
    images: `${basePath}/og-image.png`,
  },
};

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: dark)', color: '#040503' },
    { media: '(prefers-color-scheme: light)', color: '#fcfcfa' },
  ],
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" className={`${geist.variable} ${geistMono.variable}`} suppressHydrationWarning>
      <body className="flex flex-col min-h-screen">
        <Provider>{children}</Provider>
      </body>
    </html>
  );
}

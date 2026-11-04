import { createGetUrl } from 'fumadocs-core/source';

export const appName = 'supermux';
export const docsRoute = '/';
export const docsContentRoute = '/llms.mdx';

// Set at build time for a site served under a sub-path (the private preview).
export const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? '';

export const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://docs.supermux.dev';

export const gitConfig = {
  user: 'UstaLabs',
  repo: 'supermux',
  branch: 'main',
  contentDir: 'docs-site/content/pages',
};

export const links = {
  website: 'https://supermux.dev',
  github: 'https://github.com/UstaLabs/supermux',
  releases: 'https://github.com/UstaLabs/supermux/releases/latest',
  npm: 'https://www.npmjs.com/package/@supermux/core',
};

const getContentUrl = createGetUrl(docsContentRoute);

export function getPageMarkdownUrl(page: { slugs: string[]; locale?: string }) {
  const segments = [...page.slugs, 'content.md'];

  return { segments, url: getContentUrl(segments, page.locale) };
}

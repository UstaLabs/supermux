import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

// DOCS_BASE_PATH builds the site for a sub-path, e.g. /p/docs-preview for the
// private relay preview. Leave it unset for a site served at the root of its domain.
const basePath = process.env.DOCS_BASE_PATH ?? '';
// DOCS_DRAFT=1 shows the <Check> notes left for review. Never set it for a public build.
const draft = process.env.DOCS_DRAFT === '1' ? '1' : '';

/** @type {import('next').NextConfig} */
const config = {
  output: 'export',
  reactStrictMode: true,
  trailingSlash: true,
  basePath,
  images: { unoptimized: true },
  env: {
    NEXT_PUBLIC_BASE_PATH: basePath,
    NEXT_PUBLIC_DOCS_DRAFT: draft,
  },
};

export default withMDX(config);

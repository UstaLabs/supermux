import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { Brand } from '@/components/logo';
import { links } from './shared';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: <Brand />,
      url: '/',
    },
    githubUrl: links.github,
    links: [
      { text: 'supermux.dev', url: links.website, external: true },
      { text: 'Changelog', url: `${links.github}/releases`, external: true },
      { type: 'button', text: 'Download', url: links.releases, external: true, on: 'nav' },
    ],
  };
}

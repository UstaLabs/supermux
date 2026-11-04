import type { ReactNode } from 'react';

const draft = process.env.NEXT_PUBLIC_DOCS_DRAFT === '1';

// An open question for the docs' owner. Shown only in draft builds
// (DOCS_DRAFT=1); a normal build renders nothing.
export function Check({ children }: { children: ReactNode }) {
  if (!draft) return null;
  return (
    <aside className="sm-check not-prose" role="note">
      <span className="sm-check-label">To check</span>
      <div className="sm-check-body">{children}</div>
    </aside>
  );
}

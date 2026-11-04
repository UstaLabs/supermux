import type { ReactNode } from 'react';

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="sm-kbd">{children}</kbd>;
}

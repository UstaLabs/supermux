export function chooseDefaultProject(current: string, touched: boolean, recent: string[]): string {
  if (touched) return current
  return recent[0] ?? current
}

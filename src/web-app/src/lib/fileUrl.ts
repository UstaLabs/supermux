/** Same-origin file URL; browser sends the HttpOnly cmux_token cookie automatically. */
export function fileUrl(file_id: string): string {
  return `/files/${encodeURIComponent(file_id)}`
}

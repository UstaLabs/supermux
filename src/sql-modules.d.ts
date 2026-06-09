// Allow importing .sql files as text (Bun `with { type: "text" }`), so DB
// migrations are inlined into the production bundle instead of read from disk.
declare module "*.sql" {
  const content: string
  export default content
}

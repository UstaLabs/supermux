/// <reference types="vite/client" />

declare module "*.vue" {
  import type { DefineComponent } from "vue"
  const component: DefineComponent<object, object, unknown>
  export default component
}

declare const __PRODUCT_NAME__: string
declare const __APP_BUILD_ID__: string
declare const __APP_BUILD_TIME__: string

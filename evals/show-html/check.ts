// The harness uses the product's check (src/core/show-html/check.ts): one implementation. Only the signature differs:
// the harness hands in its own browser and asks for screenshots and the blocked-host list.
export {
  cardDocument, decodePng, DESKTOP, describeProblem, findBrowser, isBlank, launchBrowser, PHONE,
  type CheckResult, type Problem,
} from "../../src/core/show-html/check"
import { checkWithBrowser } from "../../src/core/show-html/check"
export const checkPage = checkWithBrowser

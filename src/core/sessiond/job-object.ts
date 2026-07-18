import { dlopen } from "bun:ffi"

export interface ProcessJob {
  assign(pid: number): void
  terminate(exitCode?: number): void
  close(): void
}

type Handle = bigint

/** @internal Narrow seam around kernel32 for deterministic non-Windows tests. */
export interface JobObjectApi {
  createJobObject(): Handle
  openProcess(access: number, inheritHandle: number, pid: number): Handle
  assignProcessToJobObject(job: Handle, process: Handle): number
  terminateJobObject(job: Handle, exitCode: number): number
  closeHandle(handle: Handle): number
  lastError(): number
}

const PROCESS_TERMINATE = 0x0001
const PROCESS_SET_QUOTA = 0x0100
const PROCESS_ACCESS = PROCESS_TERMINATE | PROCESS_SET_QUOTA

let windowsApi: JobObjectApi | undefined

function windowsError(operation: string, api: JobObjectApi, detail = ""): Error {
  const suffix = detail.length > 0 ? ` (${detail})` : ""
  return new Error(`${operation}${suffix} failed with Windows error ${api.lastError()}`)
}

/** @internal Build the public boundary from an injected native API. */
export function createProcessJobFromApi(api: JobObjectApi): ProcessJob {
  const handle = api.createJobObject()
  if (handle === 0n) throw windowsError("CreateJobObjectW", api)

  let closed = false
  let terminated = false

  const activeHandle = (): Handle => {
    if (closed) throw new Error("Process Job Object is closed")
    return handle
  }

  return {
    assign(pid) {
      if (!Number.isSafeInteger(pid) || pid <= 0) throw new RangeError("pid must be a positive integer")
      const job = activeHandle()
      const processHandle = api.openProcess(PROCESS_ACCESS, 0, pid)
      if (processHandle === 0n) throw windowsError("OpenProcess", api, `pid ${pid}`)

      let assignmentError: Error | undefined
      try {
        if (api.assignProcessToJobObject(job, processHandle) === 0) {
          assignmentError = windowsError("AssignProcessToJobObject", api, `pid ${pid}`)
        }
      } finally {
        if (api.closeHandle(processHandle) === 0 && assignmentError === undefined) {
          assignmentError = windowsError("CloseHandle", api, `process pid ${pid}`)
        }
      }
      if (assignmentError) throw assignmentError
    },
    terminate(exitCode = 1) {
      if (!Number.isInteger(exitCode) || exitCode < 0 || exitCode > 0xffff_ffff) {
        throw new RangeError("exitCode must be a uint32")
      }
      const job = activeHandle()
      if (terminated) return
      if (api.terminateJobObject(job, exitCode) === 0) throw windowsError("TerminateJobObject", api)
      terminated = true
    },
    close() {
      if (closed) return
      if (api.closeHandle(handle) === 0) throw windowsError("CloseHandle", api, "Job Object")
      closed = true
    },
  }
}

function loadWindowsApi(): JobObjectApi {
  if (windowsApi) return windowsApi
  if (process.platform !== "win32") {
    throw new Error("Windows Job Objects are only available on win32")
  }

  // HANDLE values are pointer-sized. The Windows builds we ship are 64-bit, so
  // keep the native boundary lossless by representing them as u64/bigint.
  const library = dlopen("kernel32.dll", {
    CreateJobObjectW: { args: ["u64", "u64"], returns: "u64" },
    OpenProcess: { args: ["u32", "u32", "u32"], returns: "u64" },
    AssignProcessToJobObject: { args: ["u64", "u64"], returns: "i32" },
    TerminateJobObject: { args: ["u64", "u32"], returns: "i32" },
    CloseHandle: { args: ["u64"], returns: "i32" },
    GetLastError: { args: [], returns: "u32" },
  } as const)

  const symbols = library.symbols
  windowsApi = {
    createJobObject: () => symbols.CreateJobObjectW(0n, 0n),
    openProcess: (access, inheritHandle, pid) => symbols.OpenProcess(access, inheritHandle, pid),
    assignProcessToJobObject: (job, processHandle) => symbols.AssignProcessToJobObject(job, processHandle),
    terminateJobObject: (job, exitCode) => symbols.TerminateJobObject(job, exitCode),
    closeHandle: handle => symbols.CloseHandle(handle),
    lastError: () => symbols.GetLastError(),
  }
  return windowsApi
}

export function createProcessJob(): ProcessJob {
  return createProcessJobFromApi(loadWindowsApi())
}

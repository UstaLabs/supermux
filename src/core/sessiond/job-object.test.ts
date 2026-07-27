import { describe, expect, test } from "bun:test"
import { createProcessJob, createProcessJobFromApi, type JobObjectApi } from "./job-object"

function fakeApi(overrides: Partial<JobObjectApi> = {}) {
  const calls: string[] = []
  const api: JobObjectApi = {
    createJobObject() {
      calls.push("create-job")
      return 101n
    },
    openProcess(access, inheritHandle, pid) {
      calls.push(`open-process:${access}:${inheritHandle}:${pid}`)
      return 202n
    },
    assignProcessToJobObject(job, process) {
      calls.push(`assign:${job}:${process}`)
      return 1
    },
    terminateJobObject(job, exitCode) {
      calls.push(`terminate:${job}:${exitCode}`)
      return 1
    },
    closeHandle(handle) {
      calls.push(`close:${handle}`)
      return 1
    },
    lastError() {
      return 87
    },
    ...overrides,
  }
  return { api, calls }
}

describe("ProcessJob", () => {
  test("rejects non-Windows use before attempting to load kernel32", () => {
    if (process.platform !== "win32") expect(() => createProcessJob()).toThrow(/only available on win32/i)
  })

  test("creates one unnamed job, assigns through a temporary process handle, and is idempotent", () => {
    const { api, calls } = fakeApi()
    const job = createProcessJobFromApi(api)

    job.assign(4242)
    job.terminate(9)
    job.terminate(9)
    job.close()
    job.close()

    expect(calls).toEqual([
      "create-job",
      "open-process:257:0:4242",
      "assign:101:202",
      "close:202",
      "terminate:101:9",
      "close:101",
    ])
  })

  test("fails loudly with Windows error context when job creation fails", () => {
    const { api } = fakeApi({ createJobObject: () => 0n })
    expect(() => createProcessJobFromApi(api)).toThrow(/CreateJobObjectW.*87/i)
  })

  test("closes the process handle and reports assignment failures", () => {
    const { api, calls } = fakeApi({ assignProcessToJobObject: () => 0 })
    const job = createProcessJobFromApi(api)

    expect(() => job.assign(4242)).toThrow(/AssignProcessToJobObject.*87/i)
    expect(calls.at(-1)).toBe("close:202")
    job.close()
  })

  test("closes the job if OpenProcess or termination fails", () => {
    const opened = fakeApi({ openProcess: () => 0n })
    const openJob = createProcessJobFromApi(opened.api)
    expect(() => openJob.assign(99)).toThrow(/OpenProcess.*99.*87/i)
    openJob.close()

    const terminated = fakeApi({ terminateJobObject: () => 0 })
    const terminateJob = createProcessJobFromApi(terminated.api)
    expect(() => terminateJob.terminate()).toThrow(/TerminateJobObject.*87/i)
    terminateJob.close()
  })
})

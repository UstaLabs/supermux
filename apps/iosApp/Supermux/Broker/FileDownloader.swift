import Foundation

/// One-shot streaming download to a destination URL with progress + error reporting.
///
/// Uses `URLSessionDownloadTask` so the system streams bytes to disk — a 20–30 MB file is never
/// buffered into RAM (the old `loadFile` path did, which is why large downloads could fail
/// silently). Progress is reported as a `0…1` fraction; HTTP non-2xx and transport failures are
/// thrown rather than swallowed.
final class FileDownloader: NSObject, URLSessionDownloadDelegate {
    private let dest: URL
    private let onProgress: (Double) -> Void
    private var continuation: CheckedContinuation<URL, Error>?
    /// Retained so the system doesn't deallocate the session (and cancel the task) mid-download.
    private var session: URLSession?

    init(dest: URL, onProgress: @escaping (Double) -> Void) {
        self.dest = dest
        self.onProgress = onProgress
    }

    /// Run the request to completion, returning the local file URL or throwing on failure.
    func download(_ request: URLRequest) async throws -> URL {
        try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            let cfg = URLSessionConfiguration.default
            cfg.timeoutIntervalForRequest = 60     // inactivity (no bytes) timeout
            cfg.timeoutIntervalForResource = 300   // total budget for a slow big file
            let session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
            self.session = session
            session.downloadTask(with: request).resume()
        }
    }

    /// Resume the continuation exactly once and tear down the session (breaks the delegate cycle).
    private func finish(_ result: Result<URL, Error>) {
        guard let cont = continuation else { return }
        continuation = nil
        session?.finishTasksAndInvalidate()
        session = nil
        cont.resume(with: result)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData _: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }   // size unknown → no determinate bar
        onProgress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        // A 4xx/5xx still "finishes" downloading (the error body). Treat it as a failure.
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            finish(.failure(URLError(.badServerResponse)))
            return
        }
        // The temp file at `location` is removed when this method returns — move it now.
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: location, to: dest)
            onProgress(1.0)
            finish(.success(dest))
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }   // transport failure (timeout, offline, …)
    }
}

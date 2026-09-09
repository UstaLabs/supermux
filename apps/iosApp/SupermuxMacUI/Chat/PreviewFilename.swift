import Foundation
import UniformTypeIdentifiers

/// Returns a filename whose extension lets Quick Look identify the file's type.
///
/// Quick Look keys off the URL's path extension, so a file downloaded under a name with no
/// extension (or no name at all) previews as a generic blob with only a share button. This
/// keeps an existing extension when present, otherwise derives one from the MIME type —
/// preferring the system UTI mapping (`text/plain → txt`) over a naive subtype split
/// (`text/plain → "plain"`, which is wrong).
///
/// - Parameters:
///   - name: The attachment's original filename, if any.
///   - mime: The attachment's MIME type, if any.
///   - fallbackBase: Base name used when `name` is nil/empty (default `"file"`).
///   - defaultExt: Extension used when `mime` yields none (default `nil` → no extension).
func previewFilename(name: String?, mime: String?,
                     fallbackBase: String = "file", defaultExt: String? = nil) -> String {
    if let n = name, !n.isEmpty, n.contains(".") { return n }
    let base = name.flatMap { $0.isEmpty ? nil : $0 } ?? fallbackBase
    if let ext = mimeFileExtension(mime) ?? defaultExt {
        return "\(base).\(ext)"
    }
    return base
}

/// The preferred filename extension for a MIME type (UTI-backed), falling back to the subtype.
/// Strips any `; parameters` (e.g. `text/plain; charset=utf-8`) and lowercases first, since an
/// attachment's MIME can arrive as a raw Content-Type value.
private func mimeFileExtension(_ mime: String?) -> String? {
    guard let mime else { return nil }
    let bare = mime.prefix { $0 != ";" }.trimmingCharacters(in: .whitespaces).lowercased()
    guard !bare.isEmpty else { return nil }
    if let ext = UTType(mimeType: bare)?.preferredFilenameExtension { return ext }
    return bare.split(separator: "/").last.map(String.init)
}

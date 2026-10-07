package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A downloaded attachment previews as its real type only if its filename carries the right
 * extension — every host viewer keys off the suffix, so getting this wrong turns a PDF into an
 * unopenable blob with nothing but a share button.
 */
class PreviewFilenameTest {

    /** The sender already named the type; second-guessing it can only rename the file wrongly. */
    @Test fun anExistingExtensionIsKept() {
        assertEquals("report.pdf", previewFilename("report.pdf", "application/pdf"))
        assertEquals("report.pdf", previewFilename("report.pdf", null))
        // Multi-part suffixes must survive whole — appending to `archive.tar.gz` breaks the file.
        assertEquals("archive.tar.gz", previewFilename("archive.tar.gz", null))
    }

    @Test fun anExtensionIsDerivedFromTheMimeWhenTheNameHasNone() {
        assertEquals("report.pdf", previewFilename("report", "application/pdf"))
    }

    @Test fun aMissingOrEmptyNameFallsBackToTheBase() {
        assertEquals("file.pdf", previewFilename(null, "application/pdf"))
        assertEquals("file.pdf", previewFilename("", "application/pdf"))
    }

    /** A naive subtype split yields `.plain`, which no viewer recognises. */
    @Test fun textPlainMapsToTxtNotPlain() {
        assertEquals("file.txt", previewFilename(null, "text/plain"))
    }

    /** No name and no mime: a bare base beats inventing a type. */
    @Test fun noNameAndNoMimeGivesTheBareBase() {
        assertEquals("file", previewFilename(null, null))
    }

    /** The image path passes its own base and default so a nameless photo still saves as a photo. */
    @Test fun theFallbackBaseAndDefaultExtensionAreHonoured() {
        assertEquals("image.jpg", previewFilename(null, null, fallbackBase = "image", defaultExt = "jpg"))
        // A real mime still wins over the default.
        assertEquals("image.png", previewFilename(null, "image/png", fallbackBase = "image", defaultExt = "jpg"))
    }

    /** The mime arrives as a raw `Content-Type`, parameters and casing included. */
    @Test fun mimeParametersAreStrippedAndTheTypeLowercased() {
        assertEquals("file.txt", previewFilename(null, "text/plain; charset=utf-8"))
        assertEquals("file.pdf", previewFilename(null, "APPLICATION/PDF"))
        assertEquals("file", previewFilename(null, "   "))
    }

    /** `.gitignore` is a complete name; `.gitignore.txt` is a different file. */
    @Test fun aDotfileNameIsLeftAlone() {
        assertEquals(".gitignore", previewFilename(".gitignore", "text/plain"))
    }
}

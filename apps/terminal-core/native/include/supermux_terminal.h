/*
 * supermux terminal-core: the owned, versioned C ABI over the pinned
 * libghostty-vt (see native/README.md, "st_* ABI v2").
 *
 * This is the ONLY native surface the Kotlin bindings (JNI on Android/JVM,
 * cinterop on iOS, the wasm loader in the browser) call. All terminal
 * semantics live behind it in native/src/terminal_bridge.c, so every platform
 * behaves identically:
 *
 * - No upstream (Ghostty) struct or enum crosses this ABI. Inputs are plain
 *   fixed-width integers and byte ranges using the package-owned constants of
 *   TerminalConstants.kt; outputs are self-describing codec buffers
 *   ("envelopes", see below) decoded by ViewportCodec.kt.
 * - No callbacks cross this ABI. Terminal effects (query responses, input
 *   encodings, title, bell, clipboard) are queued inside the engine and
 *   collected with st_drain_effects().
 * - Handles are opaque 32-bit values from a process-wide handle table, never
 *   pointers: 0, a destroyed handle, or a handle that was never issued fails
 *   with ST_ERR_INVALID_HANDLE instead of touching freed memory.
 * - Every function that can fail returns an st_status (0 = ST_OK, negative =
 *   error). Output parameters are written only on ST_OK.
 *
 * Threading: the process-wide handle table is safe for DIFFERENT handles on
 * different threads (st_create, st_destroy and every other call may run
 * concurrently as long as each involves a different handle). Calls on the
 * SAME handle must be externally serialized by the caller -- including
 * st_destroy against any concurrent use of that handle: a use racing its
 * destroy is undefined (the table check is not a lock). Bindings therefore
 * keep a per-engine lock plus a closed flag and never call into a handle
 * after closing it. The engine never calls back into the embedder.
 *
 * Codec envelope (all integers little-endian):
 *
 *   u32 magic = 0x53545654   u16 abi = 2   u16 kind   u32 payloadBytes
 *   payload[payloadBytes]    (the buffer is exactly 12 + payloadBytes long)
 *
 *   kind 1 = viewport, 2 = effects, 3 = selected text. payloadBytes <= 8 MiB.
 *   Strings: u32 byte length + UTF-8 bytes (always valid UTF-8; invalid input
 *   is replaced by U+FFFD). Byte arrays: u32 length + bytes. Booleans: u8 0/1.
 *   Nullable: u8 presence (0/1) then the value. Int = i32, Long = i64,
 *   colours = u64 (TerminalColor encoding). Collections: u32 count + elements.
 *   The exact payload layouts are documented in native/README.md.
 */
#ifndef SUPERMUX_TERMINAL_H
#define SUPERMUX_TERMINAL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#if defined(ST_BUILDING_SHARED)
#define ST_API __declspec(dllexport)
#else
#define ST_API
#endif
#elif defined(__GNUC__) || defined(__clang__)
#define ST_API __attribute__((visibility("default")))
#else
#define ST_API
#endif

/** ABI version implemented by this header. Bumped on any incompatible change. */
#define ST_ABI_VERSION 2u

/* ------------------------------------------------------------ status ---- */

typedef int32_t st_status;

#define ST_OK 0
/** Handle is 0, destroyed, or was never issued by st_create. */
#define ST_ERR_INVALID_HANDLE (-1)
/** st_create was asked for an ABI version this library does not implement. */
#define ST_ERR_ABI_MISMATCH (-2)
/** An argument is out of range (size, enum value, colour, point, pointer). */
#define ST_ERR_INVALID_ARGUMENT (-3)
/** An allocation failed. The engine stays usable; see per-function notes. */
#define ST_ERR_OUT_OF_MEMORY (-4)
/** A fixed limit was hit: too many live terminals, an output envelope
 *  would exceed 8 MiB, or the effect queue is full. */
#define ST_ERR_LIMIT (-5)
/** st_paste refused text that could inject commands (see ST_PASTE_ALLOW_UNSAFE). */
#define ST_ERR_REJECTED (-6)
/** Ghostty reported an unexpected failure. */
#define ST_ERR_INTERNAL (-7)

/* --------------------------------------------------------- constants ---- */

/** Maximum simultaneously live terminals per process. */
#define ST_MAX_TERMINALS 1024u
/** Maximum columns / rows (codec limit). */
#define ST_MAX_DIMENSION 4096u
/** Maximum columns x rows. Together with ST_MAX_CELL_TEXT and
 *  ST_MAX_LINK_BYTES this guarantees a worst-case full frame fits in
 *  ST_MAX_PAYLOAD, so any accepted size can always be rendered. */
#define ST_MAX_CELLS 100000u
/** Maximum UTF-8 bytes of one cell's text on the wire; a longer grapheme
 *  cluster (e.g. dozens of combining marks) is cut at a code point boundary. */
#define ST_MAX_CELL_TEXT 32u
/** Budget for the encoded links of one frame; links beyond it are omitted. */
#define ST_MAX_LINK_BYTES (1024u * 1024u)
/** Maximum codec payload in bytes (8 MiB). */
#define ST_MAX_PAYLOAD (8u * 1024u * 1024u)
/** Envelope header size in bytes. */
#define ST_ENVELOPE_HEADER 12u
#define ST_CODEC_MAGIC 0x53545654u
#define ST_KIND_VIEWPORT 1u
#define ST_KIND_EFFECTS 2u
#define ST_KIND_SELECTED_TEXT 3u

/** Number of u64 colours passed to st_colors: fg, bg, cursor, palette[256]. */
#define ST_COLOR_COUNT 259u

/** st_feed origin (OutputOrigin). */
#define ST_ORIGIN_LIVE 0u
#define ST_ORIGIN_REPLAY 1u

/** st_read_viewport flags. */
#define ST_READ_FORCE_FULL 1u   /* every row, whatever was acknowledged */
#define ST_READ_BREAK_HOLD 2u   /* end a synchronized-output hold first (owner timeout) */

/** st_read_viewport out_frame_flags bits. */
#define ST_FRAME_HELD 1u        /* frame is the one captured when a mode-2026 hold began; hold still active */

/** st_paste flags. */
#define ST_PASTE_ALLOW_UNSAFE 1u

/** Effect tags inside a kind=2 payload. */
#define ST_EFFECT_RESPONSE 1u
#define ST_EFFECT_INPUT 2u
#define ST_EFFECT_TITLE 3u
#define ST_EFFECT_BELL 4u
#define ST_EFFECT_CLIPBOARD 5u

/** Opaque terminal handle; 0 is never a valid handle. */
typedef uint32_t st_handle;

/* Optional custom allocator (Ghostty's GhosttyAllocator, allocator.h). NULL
 * everywhere means Ghostty's default allocator (libc malloc on native, the
 * module's own allocator on wasm). The wrapper never calls malloc itself. */
struct GhosttyAllocator;

/* --------------------------------------------------------- lifecycle ---- */

/** ABI version implemented by the loaded library (== ST_ABI_VERSION it was built with). */
ST_API uint32_t st_abi_version(void);

/**
 * Create a terminal.
 *
 * @param abi_version    must equal ST_ABI_VERSION, else ST_ERR_ABI_MISMATCH
 * @param columns, rows  1..ST_MAX_DIMENSION each, columns*rows <= ST_MAX_CELLS
 * @param cell_width_px, cell_height_px  1..65535; used for pixel-based mouse
 *                       encoding and XTWINOPS/in-band size reports
 * @param history_lines  scrollback line limit (0 = no scrollback)
 * @param history_bytes  scrollback byte limit (0 = no scrollback)
 *                       Both are enforced by evicting whole Ghostty pages,
 *                       oldest first, the first limit reached winning; see
 *                       README "History budgets" for the measured bounds.
 * @param allocator      TEST-ONLY hook (failure injection, accounting):
 *                       production bindings pass NULL (Ghostty's default
 *                       allocator). If given, it must outlive the terminal
 *                       AND every buffer returned for it; the struct itself
 *                       is copied. Terminal pages never use it (Ghostty
 *                       takes them from the OS page allocator).
 * @param out_handle     receives the handle on ST_OK
 */
ST_API st_status st_create(uint32_t abi_version, uint32_t columns, uint32_t rows,
                           uint32_t cell_width_px, uint32_t cell_height_px,
                           uint32_t history_lines, uint64_t history_bytes,
                           const struct GhosttyAllocator *allocator,
                           st_handle *out_handle);

/**
 * Destroy a terminal and everything it owns except buffers already returned
 * (those stay valid until st_free_buffer). Idempotent: a second call (or 0)
 * returns ST_ERR_INVALID_HANDLE and does nothing else.
 */
ST_API st_status st_destroy(st_handle handle);

/* ---------------------------------------------------------- mutation ---- */

/**
 * Parse pty output. origin = ST_ORIGIN_LIVE or ST_ORIGIN_REPLAY. REPLAY
 * updates the screen but never queues Response, Bell or ClipboardRequest
 * effects (Title still is). Malformed input never fails; parser state carries
 * across calls. Returns ST_ERR_OUT_OF_MEMORY / ST_ERR_LIMIT if an effect had
 * to be dropped (the screen is updated regardless).
 */
ST_API st_status st_feed(st_handle handle, const uint8_t *data, uint32_t len, uint32_t origin);

/** Full reset (RIS). Keeps size, colours and history limits. */
ST_API st_status st_reset(st_handle handle);

/** Resize (reflows the primary screen). Same ranges as st_create
 *  (ST_ERR_INVALID_ARGUMENT otherwise, terminal unchanged). */
ST_API st_status st_resize(st_handle handle, uint32_t columns, uint32_t rows,
                           uint32_t cell_width_px, uint32_t cell_height_px);

/**
 * Default colours. `colors` holds ST_COLOR_COUNT u64 values in TerminalColor
 * encoding (0x0000_0000_RRGGBBAA; DEFAULT and bits 32..63 are rejected):
 * [0] foreground, [1] background, [2] cursor, [3..258] palette 0..255. Alpha
 * is ignored. Remote OSC colour changes layer on top.
 */
ST_API st_status st_colors(st_handle handle, const uint64_t *colors, uint32_t count);

/**
 * Scroll so absolute row `row` (0 = oldest retained history row, the
 * TerminalPoint.row space) is the top viewport row. Clamps to
 * [0, historyRows]; negative clamps to 0.
 */
ST_API st_status st_scroll_to(st_handle handle, int64_t row);

/**
 * Set (has_selection = 1) or clear (0) the active selection. Points are
 * absolute (row as in st_scroll_to, column 0..columns-1), both ends
 * inclusive, in either order. Out-of-range points: ST_ERR_INVALID_ARGUMENT.
 */
ST_API st_status st_select(st_handle handle, uint32_t has_selection,
                           int64_t start_row, uint32_t start_column,
                           int64_t end_row, uint32_t end_column);

/* ------------------------------------------------------------- input ---- */
/* Each call queues at most one Input effect with the bytes to send to the pty
 * (none when the current terminal modes produce no bytes). */

/**
 * Key event. physical_code = TerminalKeys (USB HID usage id), text = layout
 * text without Ctrl/Meta applied (UTF-8, may be empty; text containing C0/DEL
 * bytes is ignored), modifiers = Modifiers bit set, action = KeyAction.
 */
ST_API st_status st_key(st_handle handle, uint32_t physical_code,
                        const uint8_t *text, uint32_t text_len,
                        uint32_t modifiers, uint32_t action);

/**
 * Mouse event in viewport CELL coordinates (may be outside the viewport while
 * dragging). button = MouseButton, modifiers = Modifiers, action = MouseAction.
 * Converted to the cell-centre pixel with the current cell size.
 */
ST_API st_status st_mouse(st_handle handle, int32_t column, int32_t row,
                          uint32_t button, uint32_t modifiers, uint32_t action);

/**
 * Paste UTF-8 text per the terminal's modes (bracketed paste 2004, newline
 * conversion, unsafe-byte stripping). Text that could inject commands returns
 * ST_ERR_REJECTED and queues nothing unless flags has ST_PASTE_ALLOW_UNSAFE.
 */
ST_API st_status st_paste(st_handle handle, const uint8_t *text, uint32_t len, uint32_t flags);

/** Focus change; queues ESC[I / ESC[O only while mode 1004 is enabled. */
ST_API st_status st_focus(st_handle handle, uint32_t focused);

/* ------------------------------------------------------------ output ---- */
/* Every buffer returned here is a complete envelope owned by the caller. It
 * stays valid (and unchanged) across later calls and after st_destroy, until
 * it is passed to st_free_buffer exactly once. */

/**
 * Serialize the current frame (kind = 1). Without ST_READ_FORCE_FULL the
 * frame holds only rows changed since the last acknowledged frame, unless a
 * full frame is required (first frame, resize, reset, colours, scroll,
 * unacknowledged full frame, any uncertainty), in which case `full` is set.
 * While a synchronized-output hold (mode 2026) is active the frame captured
 * when the hold began is returned, its `held` field (the last viewport
 * field) is 1 and *out_frame_flags has ST_FRAME_HELD; the owner must pass
 * ST_READ_BREAK_HOLD once its hold timeout (~1 s) expires. out_frame_flags
 * may be NULL. A frame never exceeds ST_MAX_PAYLOAD for any accepted size.
 */
ST_API st_status st_read_viewport(st_handle handle, uint32_t flags,
                                  uint8_t **out_buf, uint32_t *out_len,
                                  uint32_t *out_frame_flags);

/**
 * The frame with `generation` was drawn. Cleans dirty state only if that is
 * the most recently serialized frame and nothing has been captured since;
 * acknowledging an older frame is a no-op (ST_OK). A generation that was
 * never produced returns ST_ERR_INVALID_ARGUMENT and forces the next frame
 * to be full.
 */
ST_API st_status st_acknowledge(st_handle handle, int64_t generation);

/** Plain text of the active selection (kind = 3; "" when none), unwrapped and trimmed. */
ST_API st_status st_selected_text(st_handle handle, uint8_t **out_buf, uint32_t *out_len);

/** Remove and return all queued effects, oldest first (kind = 2; may be empty). */
ST_API st_status st_drain_effects(st_handle handle, uint8_t **out_buf, uint32_t *out_len);

/**
 * Free a buffer returned by st_read_viewport / st_selected_text /
 * st_drain_effects. NULL is a no-op (ST_OK). A pointer that is not the start
 * of such a buffer is rejected with ST_ERR_INVALID_ARGUMENT when detectable;
 * freeing the same buffer twice is undefined behaviour.
 */
ST_API st_status st_free_buffer(uint8_t *buf);

#ifdef __cplusplus
}
#endif

#endif /* SUPERMUX_TERMINAL_H */

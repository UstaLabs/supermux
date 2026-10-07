/*
 * Header-only probe for the pinned libghostty-vt C API.
 *
 * Compiled to an object file (never linked) before the library is built.
 * Every declaration the supermux terminal package depends on is referenced
 * here, so a pin bump that renames or removes one fails at this step with a
 * compiler error instead of surfacing later as a link or runtime failure.
 */
#include <stddef.h>
#include <ghostty/vt.h>

#define PROBE(sym) (void *)&sym

/* Types and layouts the wrapper will rely on. */
_Static_assert(sizeof(GhosttyCell) == 8, "GhosttyCell is a packed u64");
_Static_assert(sizeof(GhosttyRow) == 8, "GhosttyRow is a packed u64");
_Static_assert(sizeof(GhosttyColorRgb) == 3, "GhosttyColorRgb is r,g,b");
_Static_assert(sizeof(GhosttyResult) == sizeof(int), "enums are int sized");
_Static_assert(sizeof(GhosttyMode) == 2, "GhosttyMode is u16");

void *const supermux_probe_symbols[] = {
    /* terminal lifecycle + stream */
    PROBE(ghostty_terminal_new), PROBE(ghostty_terminal_free),
    PROBE(ghostty_terminal_reset), PROBE(ghostty_terminal_resize),
    PROBE(ghostty_terminal_set), PROBE(ghostty_terminal_get),
    PROBE(ghostty_terminal_get_multi), PROBE(ghostty_terminal_vt_write),
    PROBE(ghostty_terminal_vt_write_until_ground),
    PROBE(ghostty_terminal_scroll_viewport), PROBE(ghostty_terminal_grid_ref),
    PROBE(ghostty_terminal_point_from_grid_ref),
    /* render state */
    PROBE(ghostty_render_state_new), PROBE(ghostty_render_state_free),
    PROBE(ghostty_render_state_update), PROBE(ghostty_render_state_get),
    PROBE(ghostty_render_state_clean),
    PROBE(ghostty_render_state_row_iterator_new),
    PROBE(ghostty_render_state_row_iterator_free),
    PROBE(ghostty_render_state_row_iterator_next),
    PROBE(ghostty_render_state_row_iterator_next_dirty),
    PROBE(ghostty_render_state_row_get),
    PROBE(ghostty_render_state_row_cells_new),
    PROBE(ghostty_render_state_row_cells_free),
    PROBE(ghostty_render_state_row_cells_next),
    PROBE(ghostty_render_state_row_cells_select),
    PROBE(ghostty_render_state_row_cells_get),
    /* cells / grid refs / hyperlinks */
    PROBE(ghostty_cell_get), PROBE(ghostty_row_get),
    PROBE(ghostty_grid_ref_cell), PROBE(ghostty_grid_ref_graphemes),
    PROBE(ghostty_grid_ref_hyperlink_uri), PROBE(ghostty_grid_ref_style),
    /* input encoders */
    PROBE(ghostty_key_encoder_new), PROBE(ghostty_key_encoder_free),
    PROBE(ghostty_key_encoder_setopt),
    PROBE(ghostty_key_encoder_setopt_from_terminal),
    PROBE(ghostty_key_encoder_encode), PROBE(ghostty_key_event_new),
    PROBE(ghostty_key_event_free), PROBE(ghostty_key_event_set_key),
    PROBE(ghostty_key_event_set_action), PROBE(ghostty_key_event_set_mods),
    PROBE(ghostty_key_event_set_utf8),
    PROBE(ghostty_mouse_encoder_new), PROBE(ghostty_mouse_encoder_free),
    PROBE(ghostty_mouse_encoder_setopt),
    PROBE(ghostty_mouse_encoder_setopt_from_terminal),
    PROBE(ghostty_mouse_encoder_encode), PROBE(ghostty_mouse_event_new),
    PROBE(ghostty_mouse_event_free), PROBE(ghostty_focus_encode),
    PROBE(ghostty_terminal_paste), PROBE(ghostty_paste_encode),
    /* selection + formatter */
    PROBE(ghostty_terminal_select_all), PROBE(ghostty_terminal_select_word),
    PROBE(ghostty_terminal_selection_format_alloc),
    PROBE(ghostty_formatter_terminal_new), PROBE(ghostty_formatter_format_alloc),
    PROBE(ghostty_formatter_free), PROBE(ghostty_free),
    /* ABI manifest */
    PROBE(ghostty_type_json),
};

/* Option / data identifiers the wrapper uses. */
const int supermux_probe_constants[] = {
    GHOSTTY_TERMINAL_OPT_USERDATA, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
    GHOSTTY_TERMINAL_OPT_BELL, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
    GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
    GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, GHOSTTY_TERMINAL_OPT_RENDER_HOLD,
    GHOSTTY_TERMINAL_DATA_TITLE, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,
    GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, GHOSTTY_TERMINAL_DATA_MODE,
    GHOSTTY_TERMINAL_DATA_SCROLLBAR, GHOSTTY_TERMINAL_DATA_VT_GROUND,
    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
    GHOSTTY_RENDER_STATE_ROW_DATA_CELLS_RAW,
};

int supermux_probe_count(void) {
  return (int)(sizeof(supermux_probe_symbols) / sizeof(supermux_probe_symbols[0]) +
               sizeof(supermux_probe_constants) / sizeof(supermux_probe_constants[0]));
}

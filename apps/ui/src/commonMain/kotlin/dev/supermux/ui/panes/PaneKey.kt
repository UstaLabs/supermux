package dev.supermux.ui.panes

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Unique key for pane chrome registrations and newly minted group ids. */
@OptIn(ExperimentalUuidApi::class)
internal fun newPaneKey(): String = Uuid.random().toString()

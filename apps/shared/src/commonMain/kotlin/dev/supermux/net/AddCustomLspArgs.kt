package dev.supermux.net

/**
 * The 7 add-custom-LSP fields, mirroring [BrokerApi.addCustomEditorLsp] / `HostStore.lspAddCustom`
 * so a screen → app-state call carries a single bundle. One definition here in `:shared` (both apps
 * carried an identical copy beside their own LSP settings screen until cluster C1); the screens
 * themselves become one in C3.
 */
data class AddCustomLspArgs(
    val id: String,
    val label: String,
    val command: String,
    val extensions: List<String>,
    val args: List<String> = emptyList(),
    val languageId: String? = null,
    val installCmd: String? = null,
)

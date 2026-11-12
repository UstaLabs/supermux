package dev.supermux.ui.whatsnew

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.supermux.state.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.StringKey
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke

import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural

/** What the card offers: the newest edition's [version] and how many of its chapters [platform] has. */
data class WhatsNewOffer(val version: String, val chapters: Int, val platform: ReleasePlatform?)

private fun ReleasePlatform.place(): StringKey = when (this) {
    ReleasePlatform.Mac -> Str.whatsnew_place_mac
    ReleasePlatform.Windows -> Str.whatsnew_place_win
    ReleasePlatform.Linux -> Str.whatsnew_place_linux
    ReleasePlatform.Web -> Str.whatsnew_place_web
    ReleasePlatform.Android -> Str.whatsnew_place_android
    ReleasePlatform.IPhone -> Str.whatsnew_place_iphone
}

/**
 * The card after an update (#90), in the sidebar's update-card slot and in its shape: nothing opens by itself. Explore
 * opens What's new, ✕ puts it away; the caller settles the offer for both.
 */
@Composable
fun WhatsNewCard(offer: WhatsNewOffer?, onExplore: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = offer != null,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
    ) {
        offer ?: return@AnimatedVisibility
        WhatsNewCardBody(offer, onExplore, onDismiss)
    }
}

@Composable
private fun WhatsNewCardBody(offer: WhatsNewOffer, onExplore: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.md)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Space.sm)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(Stroke.hairline, cs.onSurface.copy(alpha = 0.08f), shape)
            .padding(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.lg)
            .testTag("whats_new_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Redeem, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                localized(Str.whatsnew_card_title, offer.version),
                color = cs.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp).testTag("whats_new_card_dismiss")) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = localized(Str.whatsnew_dismiss),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        val count = offer.platform?.let { localizedPlural(Plurals.whatsnew_card_count, offer.chapters, offer.chapters, localized(it.place())) }
            ?: localizedPlural(Plurals.whatsnew_card_count_anywhere, offer.chapters, offer.chapters)
        Text(
            count,
            color = cs.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(end = Space.sm),
        )
        Spacer(Modifier.height(Space.lg))
        Button(
            onClick = onExplore,
            shape = RoundedCornerShape(Radii.sm),
            // The update card's solid button: inverse ink, no brand colour.
            colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface),
            elevation = null,
            contentPadding = PaddingValues(horizontal = Space.sm),
            modifier = Modifier.fillMaxWidth().padding(end = Space.sm).height(36.dp).testTag("whats_new_card_explore"),
        ) {
            Text(localized(Str.whatsnew_card_explore), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * The shell's What's new (#90): decides once per launch whether this device is offered an edition, and settles it when
 * the card is opened or dismissed. [editions] are the offered versions, newest first; the card names the newest.
 */
@Stable
class WhatsNewState internal constructor(
    private val tracker: WhatsNewTracker,
    private val platform: ReleasePlatform?,
    private val load: suspend (String) -> String,
    private val scope: CoroutineScope,
) {
    var offer: WhatsNewOffer? by mutableStateOf(null)
        private set
    var editions: List<String> by mutableStateOf(emptyList())
        private set

    internal suspend fun start(existingInstall: Boolean) {
        val offered = tracker.start(existingInstall)
        val newest = offered.firstOrNull() ?: return
        val chapters = parseEdition(runCatching { load(newest) }.getOrDefault(""))?.chaptersFor(platform)?.size ?: return
        editions = offered
        offer = WhatsNewOffer(newest, chapters, platform)
    }

    /** Opened or dismissed: the card goes, and this version isn't offered again on this device. */
    fun settle() {
        offer = null
        scope.launch { tracker.settle() }
    }
}

/** [WhatsNewState] for this launch; nothing where [platform] is unknown. [existingInstall] is asked once: does the device already have machines paired? */
@Composable
fun rememberWhatsNew(
    settings: SettingsStore,
    currentVersion: String,
    platform: ReleasePlatform?,
    existingInstall: () -> Boolean,
    load: suspend (String) -> String = ::loadEdition,
    editions: List<String> = EDITIONS,
): WhatsNewState {
    val scope = rememberCoroutineScope()
    val state = remember(settings, currentVersion, platform) {
        WhatsNewState(WhatsNewTracker(settings, currentVersion, editions), platform, load, scope)
    }
    // A build that doesn't say which it is gets nothing: every edition is written per platform.
    LaunchedEffect(state) { if (currentVersion.isNotBlank() && platform != null) state.start(existingInstall()) }
    return state
}

/** The card, settled by either of its answers; Explore also opens the newest edition ([onOpen]). */
@Composable
fun WhatsNewSlot(state: WhatsNewState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    WhatsNewCard(
        state.offer,
        onExplore = {
            val version = state.offer?.version
            state.settle()
            version?.let(onOpen)
        },
        onDismiss = { state.settle() },
        modifier = modifier,
    )
}

/** Settings › App version's link back to What's new (#90): the newest edition this build has, where the shell opens it. */
@Composable
fun WhatsNewLink(currentVersion: String, modifier: Modifier = Modifier) {
    val open = LocalOpenWhatsNew.current ?: return
    val version = editionFor(currentVersion) ?: return
    KitTextAction(
        localized(Str.whatsnew_reopen, version),
        onClick = { open(version) },
        modifier = modifier.testTag("app_update_whats_new_link"),
    )
}

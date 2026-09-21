package dev.supermux.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.net.ChunkSource
import dev.supermux.net.PathValidation
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.state.ProjectLocationResult
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.PickedFile
import dev.supermux.workspace.ProjectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ProjectSettingsSheetTest {
    private val alpha = ProjectRef(
        "h1",
        ProjectDto(id = "a", name = "Alpha", locations = listOf(ProjectLocationDto("L1", "/home/u/projects/app"))),
    )
    private val beta = ProjectRef(
        "h1",
        ProjectDto(id = "b", name = "Beta", sortOrder = 1, locations = listOf(ProjectLocationDto("L2", "/home/u/work/fork"))),
    )
    private val elsewhere = ProjectRef("h2", ProjectDto(id = "x", name = "Other host"))
    private val catalog = listOf(alpha, beta, elsewhere)

    private fun ComposeUiTest.sheet(
        actions: ProjectSettingsActions,
        platform: FakePlatform = FakePlatform(),
        projects: List<ProjectRef> = catalog,
    ) {
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                ProjectSettingsSheet(
                    hostId = "h1", projectId = "a", projects = projects, home = "/home/u",
                    actions = actions, onDismiss = {},
                )
            }
        }
    }

    @Test
    fun renameSavesTheTrimmedNameAndRefusesBlank() = runComposeUiTest {
        var renamed: Triple<String, String, String>? = null
        sheet(ProjectSettingsActions(rename = { h, p, n -> renamed = Triple(h, p, n); null }))

        onNodeWithTag(ProjectSettingsTestIds.SAVE_NAME).assertIsNotEnabled()
        onNodeWithTag(ProjectSettingsTestIds.NAME).performTextClearance()
        onNodeWithTag(ProjectSettingsTestIds.NAME).performTextInput("   ")
        onNodeWithTag(ProjectSettingsTestIds.SAVE_NAME).assertIsNotEnabled()
        onNodeWithTag(ProjectSettingsTestIds.NAME).performTextClearance()
        onNodeWithTag(ProjectSettingsTestIds.NAME).performTextInput("  Supermux ")
        onNodeWithTag(ProjectSettingsTestIds.SAVE_NAME).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(Triple("h1", "a", "Supermux"), renamed)
    }

    @Test
    fun rendersTheLiveCatalogNotTheMutationResponse() = runComposeUiTest {
        var projects by mutableStateOf(catalog)
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform()) {
                ProjectSettingsSheet("h1", "a", projects, "/home/u", ProjectSettingsActions(), onDismiss = {})
            }
        }
        onNodeWithText("…/projects/app").assertIsDisplayed()
        // A projects_changed broadcast moved a location in.
        projects = listOf(
            alpha.copy(project = alpha.project.copy(locations = alpha.project.locations + ProjectLocationDto("L3", "/home/u/code/lib"))),
            beta, elsewhere,
        )
        onNodeWithText("…/code/lib").assertIsDisplayed()
    }

    @Test
    fun addLocationConflictOffersMoveHereWithTheOwnersLocationId() = runComposeUiTest {
        var added: String? = null
        var moved: Triple<String, String, String>? = null
        sheet(
            ProjectSettingsActions(
                validatePath = { _, _ -> PathValidation(ok = true, path = "/home/u/work/fork") },
                addLocation = { _, _, path -> added = path; ProjectLocationResult.Conflict("b") },
                moveLocation = { h, loc, p -> moved = Triple(h, loc, p); alpha.project },
            ),
        )
        onNodeWithTag(ProjectSettingsTestIds.ADD_PATH).performTextInput("~/work/fork")
        onNodeWithTag(ProjectSettingsTestIds.ADD_LOCATION).performClick()
        waitForIdle()
        assertEquals("/home/u/work/fork", added)
        onNodeWithText("Already in Beta").assertIsDisplayed()
        onNodeWithTag(ProjectSettingsTestIds.MOVE_HERE).performClick()
        waitForIdle()
        assertEquals(Triple("h1", "L2", "a"), moved)
    }

    @Test
    fun anInvalidPathShowsTheValidationErrorAndAddsNothing() = runComposeUiTest {
        var added = false
        sheet(
            ProjectSettingsActions(
                validatePath = { _, _ -> PathValidation(ok = false, error = "no such directory") },
                addLocation = { _, _, _ -> added = true; ProjectLocationResult.Failed },
            ),
        )
        onNodeWithTag(ProjectSettingsTestIds.ADD_PATH).performTextInput("/nope")
        onNodeWithTag(ProjectSettingsTestIds.ADD_LOCATION).performClick()
        waitForIdle()
        onNodeWithText("no such directory").assertIsDisplayed()
        assertEquals(false, added)
    }

    @Test
    fun moveToOffersOnlyTheSameHostsOtherProjects() = runComposeUiTest {
        var moved: Triple<String, String, String>? = null
        sheet(ProjectSettingsActions(moveLocation = { h, loc, p -> moved = Triple(h, loc, p); beta.project }))

        onNodeWithTag(ProjectSettingsTestIds.moveTo("L1")).performClick()
        onNodeWithTag(ProjectSettingsTestIds.moveTarget("x")).assertDoesNotExist()
        onNodeWithTag(ProjectSettingsTestIds.moveTarget("b")).performClick()
        waitForIdle()
        assertEquals(Triple("h1", "L1", "b"), moved)
    }

    @Test
    fun anOversizedImageIsRejectedBeforeUpload() = runComposeUiTest {
        var uploaded = false
        val huge = object : ChunkSource {
            override val size: Long = PROJECT_IMAGE_MAX_BYTES + 1
            override fun read(offset: Long, len: Int): ByteArray = error("must not read")
        }
        val platform = FakePlatform(pickResult = listOf(PickedFile("big.png", "image/png", huge)))
        sheet(ProjectSettingsActions(setImage = { _, _, _, _ -> uploaded = true; null }), platform)

        onNodeWithTag(ProjectSettingsTestIds.PICK_IMAGE).performClick()
        waitForIdle()
        onNodeWithText("That image is over 5 MB.").assertIsDisplayed()
        assertEquals(false, uploaded)
    }

    @Test
    fun aPickedImageUploadsWithItsMime() = runComposeUiTest {
        var upload: Pair<Int, String>? = null
        val platform = FakePlatform(
            pickResult = listOf(PickedFile("logo.webp", "application/octet-stream", ByteArrayChunkSource(byteArrayOf(1, 2, 3)))),
        )
        sheet(ProjectSettingsActions(setImage = { _, _, bytes, mime -> upload = bytes.size to mime; alpha.project }), platform)

        onNodeWithTag(ProjectSettingsTestIds.PICK_IMAGE).performClick()
        waitUntil(timeoutMillis = 5_000) { upload != null }
        assertEquals(3 to "image/webp", upload)
    }

    @Test
    fun newProjectAsksWhichHostWhenSeveralAndNoFilter() = runComposeUiTest {
        var created: Pair<String, String>? = null
        var opened: Pair<String, String>? = null
        setContent {
            NewProjectDialog(
                hosts = listOf(HostView("h1", null, "Laptop", true), HostView("h2", null, "Server", true)),
                initialHost = null,
                onCreate = { h, n -> created = h to n; ProjectDto(id = "new", name = n) },
                onCreated = { h, id -> opened = h to id },
                onDismiss = {},
            )
        }
        onNodeWithTag(ProjectSettingsTestIds.NEW_PROJECT_NAME).performTextInput("Docs")
        onNodeWithTag(ProjectSettingsTestIds.NEW_PROJECT_CREATE).assertIsNotEnabled()
        onNodeWithText("Server").performClick()
        onNodeWithTag(ProjectSettingsTestIds.NEW_PROJECT_CREATE).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals("h2" to "Docs", created)
        assertEquals("h2" to "new", opened)
    }

    @Test
    fun helpers() {
        val owner = ProjectDto(id = "b", name = "B", locations = listOf(ProjectLocationDto("L", "/p/app")))
        assertEquals("L", locationIdForPath(owner, "/p/app/"))
        assertNull(locationIdForPath(owner, "/p/other"))
        val src = ByteArrayChunkSource(byteArrayOf())
        assertEquals("image/jpeg", projectImageMime(PickedFile("a.JPG", "", src)))
        assertEquals("image/png", projectImageMime(PickedFile("a", "image/png", src)))
        assertNull(projectImageMime(PickedFile("a.svg", "image/svg+xml", src)))
    }
}

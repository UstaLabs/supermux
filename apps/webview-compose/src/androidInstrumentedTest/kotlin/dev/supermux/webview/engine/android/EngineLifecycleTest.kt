package dev.supermux.webview.engine.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Engines and their Activities (Task 19), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun engineFor(scenario: ActivityScenario<EngineTestActivity>): AndroidWebEngine {
        scenario.onActivity { ActivityRegistry.register(it) }
        return runBlocking {
            withContext(Dispatchers.Main) {
                (AndroidWebEngineFactory().create(EngineConfig(WebProfile.Default)) as WebEngineResult.Created).engine as AndroidWebEngine
            }
        }
    }

    @Test fun anIdleEngineGoesWithItsActivity() {
        val scenario = ActivityScenario.launch(EngineTestActivity::class.java)
        val engine = engineFor(scenario)
        scenario.close()
        instrumentation.runOnMainSync { assertFalse(engine.isUsable, "released with its Activity") }
    }

    @Test fun aLeasedEngineFinishesItsLeaseThenGoes() = EngineHarness.run {
        main { client = RecordingClient() }
        loadAndPaint(TestPages.solid("#00ff00"))
        scenario.recreate()
        assertTrue(main { isUsable }, "it still serves the lease it has")
        assertTrue(main { retired })
        assertTrue(main { context.activity == null }, "nothing keeps the destroyed Activity")
        assertFalse(main { reset() }, "and no other lease: the pool destroys it")
    }

    @Test fun theSurfaceMovesTheEngineToTheActivityItShowsIn() {
        val first = ActivityScenario.launch(EngineTestActivity::class.java)
        val engine = engineFor(first)
        var firstActivity: EngineTestActivity? = null
        var shownIn: EngineTestActivity? = null
        try {
            // The second Activity starts over the first, in its task, so the first stays alive. A second ActivityScenario
            // would start a cleared task (FLAG_ACTIVITY_CLEAR_TASK), destroying the first, and with it its idle engine.
            first.onActivity { activity ->
                firstActivity = activity
                activity.startActivity(Intent(activity, EngineTestActivity::class.java))
            }
            val second = runBlocking {
                withTimeout(10_000) {
                    var resumed: EngineTestActivity? = null
                    while (resumed == null) {
                        delay(50)
                        resumed = withContext(Dispatchers.Main) {
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<EngineTestActivity>()
                                .firstOrNull { it !== firstActivity }
                        }
                    }
                    resumed
                }
            }
            shownIn = second
            instrumentation.runOnMainSync { second.setContent { engine.Surface(Modifier.fillMaxSize()) } }
            runBlocking { delay(500) }
            instrumentation.runOnMainSync {
                assertSame(shownIn, engine.context.activity)
                assertFalse(engine.retired, "the same display")
            }
        } finally {
            instrumentation.runOnMainSync {
                engine.release()
                shownIn?.finish()
            }
            first.close()
        }
    }
}

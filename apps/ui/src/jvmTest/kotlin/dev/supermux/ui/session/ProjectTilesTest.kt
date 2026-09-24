package dev.supermux.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTilesTest {
    @Test fun home_prefix_becomes_tilde() {
        assertEquals("~/projects/supermux", homeRelativePath("/home/u/projects/supermux", "/home/u"))
    }

    @Test fun home_itself_is_tilde() {
        assertEquals("~", homeRelativePath("/home/u", "/home/u"))
    }

    @Test fun a_sibling_of_home_is_not_shortened() {
        assertEquals("/home/user2/app", homeRelativePath("/home/user2/app", "/home/u"))
    }

    @Test fun outside_home_or_unknown_home_stays_absolute() {
        assertEquals("/opt/tools/app", homeRelativePath("/opt/tools/app", "/home/u"))
        assertEquals("/home/u/app", homeRelativePath("/home/u/app", ""))
    }
}

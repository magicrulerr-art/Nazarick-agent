package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nazarick — the rules behind "every local tool is on, and the page only configures the resident ones".
 *
 * The interesting property is the negative one: a stored `localTools` payload from before the
 * redefinition must not be able to narrow the surface, whatever it names. `effective` is the only
 * place that happens, and `ToolSurfaceResolver` is its only caller, so this file is where that
 * promise is actually kept.
 */
class LocalToolGatesTest {

    @Test
    fun `every gated group is a real group, named once`() {
        assertEquals(LocalToolGates.gated.size, LocalToolGates.gated.distinct().size)
        assertTrue(LocalToolGates.gated.all { it in LocalToolGroups.all })
    }

    @Test
    fun `always-on is exactly the complement of gated`() {
        assertEquals(
            LocalToolGroups.all.filterNot { it in LocalToolGates.gated },
            LocalToolGates.alwaysOn,
        )
        assertTrue(LocalToolGates.alwaysOn.none { LocalToolGates.isGated(it) })
    }

    @Test
    fun `an empty stored list still yields every always-on group`() {
        assertEquals(LocalToolGates.alwaysOn, LocalToolGates.effective(emptyList()))
    }

    @Test
    fun `a stored group that is not gated changes nothing`() {
        // Exactly the shape of a payload written before the redefinition: a handful of ordinary
        // groups "enabled". None of them may narrow the surface, and none may add anything either.
        assertEquals(
            LocalToolGates.alwaysOn,
            LocalToolGates.effective(listOf(LocalToolOption.TimeInfo, LocalToolOption.Battery)),
        )
    }

    @Test
    fun `a stored gated group is honoured`() {
        val effective = LocalToolGates.effective(listOf(LocalToolOption.Termux))
        assertTrue(effective.contains(LocalToolOption.Termux))
        assertEquals(LocalToolGates.alwaysOn.size + 1, effective.size)
    }

    @Test
    fun `duplicates collapse and the order is the catalogue's`() {
        val effective = LocalToolGates.effective(
            listOf(LocalToolOption.Termux, LocalToolOption.Termux, LocalToolOption.Shizuku),
        )
        assertEquals(effective.distinct(), effective)
        assertEquals(LocalToolGroups.order(effective), effective)
    }

    @Test
    fun `gated choices drop everything else`() {
        assertEquals(
            listOf(LocalToolOption.Termux),
            LocalToolGates.gatedChoices(listOf(LocalToolOption.Battery, LocalToolOption.Termux)),
        )
        // A page that reads back the stale payload must show the same surface as an empty one.
        assertEquals(
            LocalToolGates.effective(emptyList()),
            LocalToolGates.effective(
                LocalToolGates.gatedChoices(listOf(LocalToolOption.Battery, LocalToolOption.Sensors)),
            ),
        )
    }

    @Test
    fun `a surface is the always-on groups plus whatever is stored`() {
        // The union happens at assembly, so a stored list can only *add* a gated group — it can
        // never take an always-on one away. (It does not add the gated groups the list omits,
        // which is exactly what their switch is for.)
        assertEquals(
            LocalToolGates.alwaysOn,
            LocalToolGates.effective(emptyList()),
        )
        val withTermux = LocalToolGates.effective(listOf(LocalToolOption.Termux))
        assertEquals(
            (LocalToolGates.alwaysOn + LocalToolOption.Termux).toSet(),
            withTermux.toSet(),
        )
        assertEquals(LocalToolGroups.order(withTermux), withTermux)
    }

    @Test
    fun `only the prerequisites that can be missing are gated`() {
        assertTrue(LocalToolGates.gated.isNotEmpty())
        assertTrue(LocalToolGates.isGated(LocalToolOption.Termux))
        assertTrue(LocalToolGates.isGated(LocalToolOption.ScreenAutomation))
        // An ordinary runtime permission is not a reason to hide a group: it is one system dialog,
        // and Settings -> Permissions already owns the granting.
        assertFalse(LocalToolGates.isGated(LocalToolOption.Battery))
        assertFalse(LocalToolGates.isGated(LocalToolOption.Files))
        assertFalse(LocalToolGates.isGated(LocalToolOption.Contacts))
        assertFalse(LocalToolGates.isGated(LocalToolOption.Location))
    }

    // ── v0.8 guardian curation: the per-assistant allow-list ───────────────────────────────

    @Test
    fun `a null allow-list leaves the surface byte-identical`() {
        assertEquals(
            LocalToolGates.effective(emptyList()),
            LocalToolGates.effective(emptyList(), null),
        )
        assertEquals(
            LocalToolGates.effective(listOf(LocalToolOption.Termux)),
            LocalToolGates.effective(listOf(LocalToolOption.Termux), null),
        )
    }

    @Test
    fun `an allow-list intersects and a left-out group cannot come back`() {
        val allow = listOf(LocalToolOption.TimeInfo, LocalToolOption.Files)
        val surface = LocalToolGates.effective(emptyList(), allow)
        assertEquals(LocalToolGroups.order(allow), surface)
        // a stored gated group OUTSIDE the allow-list is still out — the ceiling wins
        val withOutsideGated = LocalToolGates.effective(listOf(LocalToolOption.Termux), allow)
        assertEquals(surface, withOutsideGated)
    }

    @Test
    fun `a gated group inside the allow-list still needs the user's switch`() {
        // The allow-list grants, the gate still conditions: Termux is in the allow-list but the
        // user never switched it on, so it must not appear.
        val allow = listOf(LocalToolOption.TimeInfo, LocalToolOption.Termux)
        val notSwitched = LocalToolGates.effective(emptyList(), allow)
        assertFalse(LocalToolOption.Termux in notSwitched)
        assertTrue(LocalToolOption.TimeInfo in notSwitched)
        // switched on (and still allowed) it does.
        val switched = LocalToolGates.effective(listOf(LocalToolOption.Termux), allow)
        assertTrue(LocalToolOption.Termux in switched)
    }

    @Test
    fun `an empty allow-list is an owned-empty surface`() {
        assertEquals(emptyList<LocalToolOption>(), LocalToolGates.effective(emptyList(), emptyList()))
        assertEquals(emptyList<LocalToolOption>(), LocalToolGates.effective(listOf(LocalToolOption.Termux), emptyList()))
    }
}

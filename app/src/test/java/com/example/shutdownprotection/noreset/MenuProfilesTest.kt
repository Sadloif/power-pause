package com.example.shutdownprotection.noreset

import com.example.shutdownprotection.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuProfilesTest {
    private val poco = DeviceDescriptor(
        model = "M2102J20SG",
        api = 33,
        display = "TKQ1.221013.002 test-keys",
        incremental = "V14.0.3.0.TJUMIXM",
        manufacturer = "Xiaomi",
    )

    @Test fun pocoProfileIsCompatibilityOnlyAndRenoSelectionIsPreserved() {
        assertEquals(MenuProfile.POCO, poco.select(compatibility = true))
        assertNull(poco.select(compatibility = false))

        val reno = DeviceDescriptor(
            model = "CPH2825",
            api = 36,
            display = "CPH2825_16.0.10.501(EX01)",
            incremental = "unrestricted",
            manufacturer = "OPPO",
        )
        assertEquals(MenuProfile.RENO, reno.select(compatibility = false))
        assertEquals(MenuProfile.RENO, reno.select(compatibility = true))

        // The variant's generated flag is the same one the service uses.
        assertEquals(if (BuildConfig.COMPATIBILITY_EDITION) MenuProfile.POCO else null,
            poco.select(compatibility = BuildConfig.COMPATIBILITY_EDITION))
    }

    @Test fun pocoProfileRejectsAnyUnverifiedDeviceOrBuildField() {
        val mismatches = listOf(
            poco.copy(model = "M2102J20SI"),
            poco.copy(api = 32),
            poco.copy(display = "TKQ1.221013.002"),
            poco.copy(incremental = "V14.0.3.0.TJUMIXM-test"),
            poco.copy(manufacturer = "Redmi"),
        )
        mismatches.forEach { assertNull(it.select(compatibility = true)) }
    }

    @Test fun pocoWindowFingerprintRejectsEveryRequiredWindowMismatch() {
        assertTrue(PocoMenuFingerprint.window(
            id = 12,
            candidate = 12,
            type = 3,
            active = true,
            focused = true,
            pkg = "com.android.systemui",
            rootClass = "android.widget.FrameLayout",
            title = null,
        ))

        val mismatches = listOf(
            WindowDescriptor(id = 12, candidate = 11),
            WindowDescriptor(candidate = -1),
            WindowDescriptor(type = 2),
            WindowDescriptor(active = false),
            WindowDescriptor(focused = false),
            WindowDescriptor(pkg = "com.example"),
            WindowDescriptor(rootClass = "android.app.Dialog"),
            WindowDescriptor(title = "MiuiGlobalActions"),
        )
        mismatches.forEach { assertFalse(it.matches()) }
    }

    @Test fun pocoHierarchyRejectsMismatchesAtEveryCapturedNode() {
        val expected = expectedMenuNodes()
        assertTrue(PocoMenuFingerprint.hierarchy(expected))

        expected.indices.forEach { index ->
            val original = expected[index]
            val changed = listOf(
                original.copy(clazz = "android.view.ViewGroup"),
                original.copy(pkg = "com.example"),
                original.copy(id = if (original.id == null) "android:id/wrong" else "android:id/other"),
                original.copy(description = if (original.description == null) "wrong" else "wrong"),
                original.copy(children = original.children + 1),
            )
            changed.forEach { node ->
                val altered = expected.toMutableList().apply { this[index] = node }
                assertFalse("Unexpected match at node $index: $node", PocoMenuFingerprint.hierarchy(altered))
            }
        }
    }

    private data class WindowDescriptor(
        val id: Int = 12,
        val candidate: Int = 12,
        val type: Int = 3,
        val active: Boolean = true,
        val focused: Boolean = true,
        val pkg: String = "com.android.systemui",
        val rootClass: String = "android.widget.FrameLayout",
        val title: String? = null,
    ) {
        fun matches() = PocoMenuFingerprint.window(id, candidate, type, active, focused, pkg, rootClass, title)
    }

    private fun expectedMenuNodes(): List<MenuNode> {
        val classes = listOf(
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.FrameLayout",
            "android.widget.FrameLayout",
        ) + List(5) { "android.view.View" }
        val children = listOf(1, 1, 1, 5, 0, 0, 0, 0, 0)
        val descriptions = listOf(null, null, null, null, "Back", "Aeroplane", "Silent", "Reboot", "Power off")
        return classes.indices.map { index ->
            MenuNode(
                pkg = "com.android.systemui",
                clazz = classes[index],
                id = if (index == 2) "android:id/content" else null,
                description = descriptions[index],
                children = children[index],
            )
        }
    }

    private data class DeviceDescriptor(
        val model: String,
        val api: Int,
        val display: String,
        val incremental: String,
        val manufacturer: String,
    ) {
        fun select(compatibility: Boolean) = MenuProfiles.select(
            compatibility, model, api, display, incremental, manufacturer,
        )
    }
}

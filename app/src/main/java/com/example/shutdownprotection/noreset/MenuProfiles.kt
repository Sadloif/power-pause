package com.example.shutdownprotection.noreset

/** Profiles are selected explicitly; accepting an event alone never authorizes Back. */
enum class MenuProfile { RENO, POCO }

object MenuProfiles {
    fun select(compatibility: Boolean, model: String, api: Int, display: String,
               incremental: String, manufacturer: String): MenuProfile? = when {
        model == "CPH2825" && api == 36 && display == "CPH2825_16.0.10.501(EX01)" -> MenuProfile.RENO
        compatibility && model == "M2102J20SG" && api == 33 &&
            display == "TKQ1.221013.002 test-keys" && incremental == "V14.0.3.0.TJUMIXM" &&
            manufacturer.equals("Xiaomi", ignoreCase = true) -> MenuProfile.POCO
        else -> null
    }
}

/** Only nine nodes along the captured menu layout; no general screen traversal or text. */
data class MenuNode(val pkg: String?, val clazz: String?, val id: String?,
                    val description: String?, val children: Int)

object PocoMenuFingerprint {
    fun event(type: Int, pkg: String?, clazz: String?): Boolean =
        type == 32 && pkg == "com.android.systemui" && clazz == "android.app.Dialog"

    fun window(id: Int, candidate: Int, type: Int, active: Boolean, focused: Boolean,
               pkg: String?, rootClass: String?, title: String?): Boolean =
        candidate >= 0 && id == candidate && type == 3 && active && focused &&
            pkg == "com.android.systemui" && rootClass == "android.widget.FrameLayout" && title == null

    fun hierarchy(nodes: List<MenuNode>): Boolean {
        if (nodes.size != 9 || nodes.any { it.pkg != "com.android.systemui" }) return false
        val classes = listOf("android.widget.FrameLayout", "android.widget.LinearLayout",
            "android.widget.FrameLayout", "android.widget.FrameLayout") + List(5) { "android.view.View" }
        val children = listOf(1, 1, 1, 5, 0, 0, 0, 0, 0)
        val labels = listOf(null, null, null, null, "Back", "Aeroplane", "Silent", "Reboot", "Power off")
        return nodes.indices.all { index ->
            val node = nodes[index]
            node.clazz == classes[index] && node.children == children[index] &&
                node.description == labels[index] && node.id == if (index == 2) "android:id/content" else null
        }
    }
}

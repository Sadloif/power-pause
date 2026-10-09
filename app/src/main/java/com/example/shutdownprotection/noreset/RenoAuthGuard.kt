package com.example.shutdownprotection.noreset

data class RenoAuthNode(val clazz: String?, val id: String?, val children: Int)

object RenoAuthFingerprint {
    fun event(type: Int, pkg: String?, clazz: String?): Boolean =
        type == 32 && pkg == "com.android.systemui" && clazz == "android.widget.LinearLayout"

    fun window(
        id: Int,
        candidate: Int,
        type: Int,
        active: Boolean,
        focused: Boolean,
        pkg: String?,
        rootClass: String?,
        title: String?,
    ): Boolean = candidate >= 0 && id == candidate && type == 3 && active && focused &&
        pkg == "com.android.systemui" && rootClass == "android.widget.LinearLayout" && title == " "

    fun hierarchy(nodes: List<RenoAuthNode>, heading: String?): Boolean =
        heading == AUTH_HEADING && nodes == signature

    private val signature = listOf(
        RenoAuthNode("android.widget.LinearLayout", null, 1),
        RenoAuthNode("android.widget.FrameLayout", "com.android.systemui:id/layout", 4),
        RenoAuthNode("android.widget.ImageView", "com.android.systemui:id/background", 0),
        RenoAuthNode("android.view.View", "com.android.systemui:id/panel", 0),
        RenoAuthNode("android.widget.LinearLayout", null, 3),
        RenoAuthNode("android.widget.RelativeLayout", null, 2),
        RenoAuthNode("android.widget.ImageView", "com.android.systemui:id/cancel", 0),
        RenoAuthNode("android.widget.ImageView", "com.android.systemui:id/save", 0),
        RenoAuthNode("android.widget.ScrollView", "com.android.systemui:id/scrollView", 1),
        RenoAuthNode("android.widget.FrameLayout", null, 1),
        RenoAuthNode("android.widget.LinearLayout", null, 1),
        RenoAuthNode("android.widget.TextView", "com.android.systemui:id/title", 0),
        RenoAuthNode("android.widget.FrameLayout", null, 1),
        RenoAuthNode("android.view.ViewGroup", "com.android.systemui:id/input_layout", 1),
        RenoAuthNode("android.widget.ScrollView", "com.android.systemui:id/biometric_scrollview", 0),
    )

    private const val AUTH_HEADING = "Enter Lock screen password"
}

/** Short-lived context tying a generic system authentication window to a trusted Reno power menu. */
class RenoShutdownContext {
    data class Snapshot(
        val menuWindowId: Int,
        val revision: Long,
        val begunElapsed: Long,
        val lastVerifiedMenuElapsed: Long,
        val episodeId: Long,
    )

    private data class Token(
        val menuWindowId: Int,
        val revision: Long,
        val begunElapsed: Long,
        val lastVerifiedMenuElapsed: Long,
        val episodeId: Long,
    )

    private var token: Token? = null
    private var nextEpisodeId = 0L

    fun begin(
        menuWindowId: Int,
        revision: Long,
        elapsed: Long,
        eventUptime: Long,
        nowUptime: Long,
    ): Boolean {
        if (menuWindowId < 0 || elapsed < 0 || eventUptime < 0 || nowUptime < eventUptime ||
            nowUptime - eventUptime > MAX_MENU_EVENT_AGE_MS) {
            token = null
            return false
        }

        val current = token
        if (current != null && (elapsed < current.begunElapsed || elapsed < current.lastVerifiedMenuElapsed)) {
            token = null
            return false
        }
        if (current != null && current.menuWindowId == menuWindowId && current.revision == revision &&
            isWithinEpisode(current, revision, elapsed)) return false

        nextEpisodeId++
        token = Token(menuWindowId, revision, elapsed, elapsed, nextEpisodeId)
        return true
    }

    /** Read without expiring a context merely because its short auth bridge elapsed. */
    fun peek(revision: Long, elapsed: Long): Snapshot? {
        val current = token ?: return null
        if (!isWithinEpisode(current, revision, elapsed) || elapsed < current.lastVerifiedMenuElapsed) {
            token = null
            return null
        }
        return Snapshot(current.menuWindowId, current.revision, current.begunElapsed,
            current.lastVerifiedMenuElapsed, current.episodeId)
    }

    /** Refresh only after the service verifies the active menu window's exact identity. */
    fun markMenuVerified(episodeId: Long, revision: Long, elapsed: Long): Boolean {
        val current = token ?: return false
        if (!isWithinEpisode(current, revision, elapsed) || elapsed < current.lastVerifiedMenuElapsed) {
            token = null
            return false
        }
        if (current.episodeId != episodeId) return false
        token = current.copy(lastVerifiedMenuElapsed = elapsed)
        return true
    }

    fun permits(revision: Long, elapsed: Long): Boolean {
        val current = token ?: return false
        if (!isWithinEpisode(current, revision, elapsed) || elapsed < current.lastVerifiedMenuElapsed) {
            token = null
            return false
        }
        return elapsed - current.lastVerifiedMenuElapsed < AUTH_BRIDGE_MS
    }

    fun consume(revision: Long, elapsed: Long): Int? {
        if (!permits(revision, elapsed)) return null
        val menuWindowId = token?.menuWindowId ?: return null
        token = null
        return menuWindowId
    }

    fun clear() {
        token = null
    }

    private fun isWithinEpisode(current: Token, revision: Long, elapsed: Long): Boolean =
        current.revision == revision && elapsed >= current.begunElapsed &&
            elapsed - current.begunElapsed < MAX_EPISODE_MS

    private companion object {
        const val MAX_MENU_EVENT_AGE_MS = 750L
        const val AUTH_BRIDGE_MS = 1_500L
        const val MAX_EPISODE_MS = 60_000L
    }
}

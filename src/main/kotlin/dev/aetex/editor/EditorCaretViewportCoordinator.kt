package dev.aetex.editor

import kotlin.math.ceil
import kotlin.math.floor

/** A vertical rectangle in the editor's laid-out text coordinate space. */
data class EditorCaretBounds(
    val top: Float,
    val bottom: Float
)

/**
 * The small part of a text layout needed to reveal a caret. Implementations must
 * describe the exact canonical text revision identified by [textRevision].
 */
interface EditorCaretLayout {
    val documentRevision: Long
    val textRevision: Long
    val text: String

    /** Returns the caret row, using the visual line at [offset] as a fallback. */
    fun caretBounds(offset: Int): EditorCaretBounds
}

data class EditorViewport(
    val scroll: Int,
    val maximumScroll: Int,
    val height: Int,
    val textTopPadding: Float,
    val revealMargin: Float
)

data class EditorCaretReveal(
    val requestRevision: Long,
    val targetScroll: Int,
    val requiresScroll: Boolean
)

/**
 * Coordinates canonical caret actions with the editor's shared external
 * viewport. A request is retained until an exact-revision layout can fulfil it.
 * Viewport and layout changes never create requests by themselves.
 */
class EditorCaretViewportCoordinator {
    private data class CanonicalCaret(
        val documentRevision: Long,
        val textRevision: Long,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private data class PendingReveal(
        val requestRevision: Long,
        val caret: CanonicalCaret
    )

    private var nextRequestRevision = 0L
    private var canonicalCaret: CanonicalCaret? = null
    private var pendingReveal: PendingReveal? = null

    /** Activating or replacing a document invalidates all commands for the old one. */
    fun activateDocument(
        documentRevision: Long,
        textRevision: Long,
        text: String,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        canonicalCaret = canonicalCaret(
            documentRevision,
            textRevision,
            text,
            selectionStart,
            selectionEnd
        )
        requestReveal()
    }

    /** Closing a document makes every outstanding layout or command obsolete. */
    fun closeDocument() {
        canonicalCaret = null
        pendingReveal = null
        nextRequestRevision++
    }

    /**
     * Records a canonical editor action. Identical republication is ignored, so
     * recomposition and unrelated workspace state cannot cause a reveal.
     */
    fun onEditorAction(
        documentRevision: Long,
        textRevision: Long,
        text: String,
        selectionStart: Int,
        selectionEnd: Int
    ): Boolean {
        val updated = canonicalCaret(
            documentRevision,
            textRevision,
            text,
            selectionStart,
            selectionEnd
        )
        if (updated == canonicalCaret) return false
        canonicalCaret = updated
        requestReveal()
        return true
    }

    /** User-driven viewport motion wins over a reveal that has not yet completed. */
    fun onManualViewportChange(): Boolean {
        if (pendingReveal == null) return false
        pendingReveal = null
        nextRequestRevision++
        return true
    }

    /**
     * Resolves only the newest request and only against its exact document/text
     * revision. The returned command remains guarded until [complete] is called.
     */
    fun resolve(
        layout: EditorCaretLayout,
        viewport: EditorViewport
    ): EditorCaretReveal? {
        val pending = pendingReveal ?: return null
        val caret = pending.caret
        if (
            layout.documentRevision != caret.documentRevision ||
            layout.textRevision != caret.textRevision ||
            layout.text != caret.text ||
            viewport.height <= 0
        ) {
            return null
        }

        val bounds = layout.caretBounds(caret.selectionEnd)
        val caretTop = viewport.textTopPadding + bounds.top
        val caretBottom = viewport.textTopPadding + bounds.bottom
        val visibleTop = viewport.scroll.toFloat()
        val visibleBottom = visibleTop + viewport.height
        val margin = viewport.revealMargin
            .coerceAtLeast(0f)
            .coerceAtMost(viewport.height / 2f)

        val unclampedTarget = when {
            caretTop < visibleTop + margin -> floor(caretTop - margin).toInt()
            caretBottom > visibleBottom - margin ->
                ceil(caretBottom + margin - viewport.height).toInt()
            else -> viewport.scroll
        }
        val target = unclampedTarget.coerceIn(0, viewport.maximumScroll.coerceAtLeast(0))
        return EditorCaretReveal(
            requestRevision = pending.requestRevision,
            targetScroll = target,
            requiresScroll = target != viewport.scroll
        )
    }

    fun isCurrent(reveal: EditorCaretReveal): Boolean =
        pendingReveal?.requestRevision == reveal.requestRevision

    fun complete(reveal: EditorCaretReveal): Boolean {
        if (!isCurrent(reveal)) return false
        pendingReveal = null
        return true
    }

    private fun requestReveal() {
        val caret = requireNotNull(canonicalCaret)
        pendingReveal = PendingReveal(++nextRequestRevision, caret)
    }

    private fun canonicalCaret(
        documentRevision: Long,
        textRevision: Long,
        text: String,
        selectionStart: Int,
        selectionEnd: Int
    ): CanonicalCaret {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(0, text.length)
        return CanonicalCaret(documentRevision, textRevision, text, start, end)
    }
}

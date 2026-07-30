package dev.aetex.preview.cache

import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.RenderPriority
import dev.aetex.preview.domain.RenderedPage
import java.util.LinkedHashMap

data class PageCachePolicy(
    val maximumBytes: Long = 192L * 1024 * 1024
) {
    init {
        require(maximumBytes > 0)
    }
}

data class PageCacheStats(
    val hits: Long,
    val misses: Long,
    val insertions: Long,
    val evictions: Long,
    val rejections: Long,
    val retainedBytes: Long,
    val reservedBytes: Long,
    val entryCount: Int,
    val pinnedKeyCount: Int,
    val pinOwnerCount: Int
)

enum class CacheAdmission {
    ADMITTED,
    REPLACED,
    REJECTED_TOO_LARGE,
    REJECTED_PINNED_BUDGET
}

class CacheReservation internal constructor(
    internal val owner: PageCache,
    internal val bytes: Long
) : AutoCloseable {
    internal var active: Boolean = true

    override fun close() {
        owner.release(this)
    }
}

class PageCache(
    private val policy: PageCachePolicy = PageCachePolicy()
) : AutoCloseable {
    val maximumBytes: Long
        get() = policy.maximumBytes
    private val lock = Any()
    private val entries = LinkedHashMap<PageRenderKey, CacheEntry>(16, 0.75f, true)
    private val pinsByConsumer = LinkedHashMap<String, Set<PageRenderKey>>()
    private val retiredGenerations = LinkedHashSet<GenerationId>()
    private var retainedBytes = 0L
    private var reservedBytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var insertions = 0L
    private var evictions = 0L
    private var rejections = 0L
    private var closed = false

    fun get(key: PageRenderKey): RenderedPage? = synchronized(lock) {
        if (closed || key.generationId in retiredGenerations) {
            misses++
            return@synchronized null
        }
        val entry = entries[key]
        if (entry == null) misses++ else hits++
        entry?.page
    }

    fun put(page: RenderedPage, priority: RenderPriority): CacheAdmission = synchronized(lock) {
        putInternal(page, priority)
    }

    fun reserve(estimatedBytes: Long): CacheReservation? = synchronized(lock) {
        if (closed || estimatedBytes <= 0 || estimatedBytes > policy.maximumBytes) {
            rejections++
            return@synchronized null
        }
        evictFor(estimatedBytes)
        if (retainedBytes + reservedBytes + estimatedBytes > policy.maximumBytes) {
            rejections++
            return@synchronized null
        }
        reservedBytes += estimatedBytes
        CacheReservation(this, estimatedBytes)
    }

    fun putReserved(
        page: RenderedPage,
        priority: RenderPriority,
        reservation: CacheReservation
    ): CacheAdmission = synchronized(lock) {
        require(reservation.owner === this) {
            "A cache reservation can only be committed by its owning cache."
        }
        require(reservation.active) { "Cache reservation was already released." }
        reservation.active = false
        reservedBytes -= reservation.bytes
        putInternal(page, priority)
    }

    internal fun release(reservation: CacheReservation) = synchronized(lock) {
        if (!reservation.active) return@synchronized
        reservation.active = false
        if (closed) return@synchronized
        reservedBytes -= reservation.bytes
    }

    private fun putInternal(page: RenderedPage, priority: RenderPriority): CacheAdmission {
        if (closed || page.key.generationId in retiredGenerations) {
            rejections++
            return CacheAdmission.REJECTED_PINNED_BUDGET
        }
        val weight = page.estimatedCacheBytes
        if (weight > policy.maximumBytes) {
            rejections++
            return CacheAdmission.REJECTED_TOO_LARGE
        }
        val previous = entries.remove(page.key)
        if (previous != null) retainedBytes -= previous.weight

        evictFor(weight)
        if (retainedBytes + weight > policy.maximumBytes) {
            if (previous != null) {
                entries[previous.page.key] = previous
                retainedBytes += previous.weight
            }
            rejections++
            return CacheAdmission.REJECTED_PINNED_BUDGET
        }

        entries[page.key] = CacheEntry(page, weight, retentionClass(priority))
        retainedBytes += weight
        insertions++
        return if (previous == null) CacheAdmission.ADMITTED else CacheAdmission.REPLACED
    }

    fun updatePins(consumerKey: String, keys: Set<PageRenderKey>) = synchronized(lock) {
        if (closed) return@synchronized
        val eligible = keys.filterTo(linkedSetOf()) {
            it.generationId !in retiredGenerations
        }
        if (eligible.isEmpty()) {
            pinsByConsumer.remove(consumerKey)
        } else {
            pinsByConsumer[consumerKey] = eligible
        }
        removeRetiredUnpinned()
    }

    fun releasePins(consumerKey: String) = synchronized(lock) {
        pinsByConsumer.remove(consumerKey)
        removeRetiredUnpinned()
    }

    fun invalidateGeneration(generationId: GenerationId) = synchronized(lock) {
        retiredGenerations += generationId
        while (retiredGenerations.size > MAXIMUM_RETIRED_TOMBSTONES) {
            val removable = retiredGenerations.firstOrNull { retired ->
                entries.keys.none { it.generationId == retired }
            } ?: break
            retiredGenerations.remove(removable)
        }
        removeRetiredUnpinned()
    }

    fun stats(): PageCacheStats = synchronized(lock) {
        PageCacheStats(
            hits,
            misses,
            insertions,
            evictions,
            rejections,
            retainedBytes,
            reservedBytes,
            entries.size,
            pinsByConsumer.values.asSequence().flatten().distinct().count(),
            pinsByConsumer.size
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        entries.clear()
        pinsByConsumer.clear()
        retiredGenerations.clear()
        retainedBytes = 0
        reservedBytes = 0
    }

    private fun evictFor(required: Long) {
        while (retainedBytes + reservedBytes + required > policy.maximumBytes) {
            val candidate = entries.entries
                .asSequence()
                .filterNot { isPinned(it.key) }
                .minWithOrNull(
                    compareBy<Map.Entry<PageRenderKey, CacheEntry>>(
                        { if (it.key.generationId in retiredGenerations) 0 else 1 },
                        { it.value.retentionClass }
                    )
                ) ?: return
            entries.remove(candidate.key)
            retainedBytes -= candidate.value.weight
            evictions++
        }
    }

    private fun removeRetiredUnpinned() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.generationId in retiredGenerations && !isPinned(entry.key)) {
                retainedBytes -= entry.value.weight
                iterator.remove()
                evictions++
            }
        }
    }

    private fun isPinned(key: PageRenderKey): Boolean =
        pinsByConsumer.values.any { key in it }

    /**
     * Smaller values are evicted first. Speculative entries therefore leave
     * before visible/initial pages; access order resolves equal classes as LRU.
     */
    private fun retentionClass(priority: RenderPriority): Int = when (priority) {
        RenderPriority.NEIGHBOR,
        RenderPriority.NEIGHBOR_IN_DIRECTION,
        RenderPriority.LOW -> 0

        RenderPriority.VISIBLE_REFINEMENT -> 1
        RenderPriority.DIRECT_NAVIGATION,
        RenderPriority.OTHER_VISIBLE -> 2

        RenderPriority.INITIAL,
        RenderPriority.CURRENT_VISIBLE -> 3
    }

    private data class CacheEntry(
        val page: RenderedPage,
        val weight: Long,
        val retentionClass: Int
    )

    companion object {
        /**
         * Scheduler generation eligibility is the authoritative late-write
         * guard. Cache tombstones cover recent direct races without growing
         * for the complete lifetime of a long-running project.
         */
        private const val MAXIMUM_RETIRED_TOMBSTONES = 256
    }
}

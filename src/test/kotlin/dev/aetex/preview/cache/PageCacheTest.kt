package dev.aetex.preview.cache

import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.RasterImage
import dev.aetex.preview.domain.RenderPriority
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.domain.RenderedPage
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PageCacheTest {
    @Test
    fun `reports miss then exact generation key hit`() {
        val page = page(GenerationId.create(), 0, 1)
        PageCache(PageCachePolicy(page.estimatedCacheBytes)).use { cache ->
            assertNull(cache.get(page.key))
            assertEquals(CacheAdmission.ADMITTED, cache.put(page, RenderPriority.CURRENT_VISIBLE))
            assertSame(page, cache.get(page.key))
            assertEquals(1, cache.stats().hits)
            assertEquals(1, cache.stats().misses)
        }
    }

    @Test
    fun `weight includes retained RGB and estimated UI copy`() {
        val page = page(GenerationId.create(), 0, 2)

        assertEquals(2 * 2 * 3L + 2 * 2 * 4L + 256L, page.estimatedCacheBytes)
    }

    @Test
    fun `evicts least recently used equal-priority entry by bytes`() {
        val generation = GenerationId.create()
        val first = page(generation, 0, 1)
        val second = page(generation, 1, 2)
        val third = page(generation, 2, 3)
        val budget = first.estimatedCacheBytes + second.estimatedCacheBytes
        PageCache(PageCachePolicy(budget)).use { cache ->
            cache.put(first, RenderPriority.OTHER_VISIBLE)
            cache.put(second, RenderPriority.OTHER_VISIBLE)
            cache.get(first.key)
            cache.put(third, RenderPriority.OTHER_VISIBLE)

            assertSame(first, cache.get(first.key))
            assertNull(cache.get(second.key))
            assertSame(third, cache.get(third.key))
        }
    }

    @Test
    fun `evicts speculative entry before visible entry`() {
        val generation = GenerationId.create()
        val visible = page(generation, 0, 1)
        val neighbor = page(generation, 1, 2)
        val incoming = page(generation, 2, 3)
        val budget = visible.estimatedCacheBytes + neighbor.estimatedCacheBytes
        PageCache(PageCachePolicy(budget)).use { cache ->
            cache.put(visible, RenderPriority.CURRENT_VISIBLE)
            cache.put(neighbor, RenderPriority.NEIGHBOR)
            cache.put(incoming, RenderPriority.OTHER_VISIBLE)

            assertSame(visible, cache.get(visible.key))
            assertNull(cache.get(neighbor.key))
        }
    }

    @Test
    fun `rejects a single page larger than byte budget`() {
        val page = page(GenerationId.create(), 0, 1)
        PageCache(PageCachePolicy(page.estimatedCacheBytes - 1)).use { cache ->
            assertEquals(
                CacheAdmission.REJECTED_TOO_LARGE,
                cache.put(page, RenderPriority.CURRENT_VISIBLE)
            )
            assertEquals(0, cache.stats().entryCount)
        }
    }

    @Test
    fun `byte reservations prevent concurrent renders from exceeding hard ceiling`() {
        PageCache(PageCachePolicy(1_000)).use { cache ->
            val first = cache.reserve(700)

            assertTrue(first != null)
            assertNull(cache.reserve(400))
            assertEquals(700L, cache.stats().reservedBytes)

            first.close()
            val second = cache.reserve(400)
            assertTrue(second != null)
            second.close()
            assertEquals(0L, cache.stats().reservedBytes)
        }
    }

    @Test
    fun `reservation cannot be committed by a different cache`() {
        val page = page(GenerationId.create(), 0, 1)
        PageCache(PageCachePolicy(1_000)).use { owner ->
            PageCache(PageCachePolicy(1_000)).use { other ->
                val reservation = checkNotNull(owner.reserve(page.estimatedCacheBytes))

                assertFailsWith<IllegalArgumentException> {
                    other.putReserved(page, RenderPriority.CURRENT_VISIBLE, reservation)
                }
                assertEquals(page.estimatedCacheBytes, owner.stats().reservedBytes)
                assertEquals(0L, other.stats().reservedBytes)
                reservation.close()
            }
        }
    }

    @Test
    fun `generation invalidation never returns its entries`() {
        val generation = GenerationId.create()
        val page = page(generation, 0, 1)
        PageCache(PageCachePolicy(page.estimatedCacheBytes)).use { cache ->
            cache.put(page, RenderPriority.CURRENT_VISIBLE)
            cache.invalidateGeneration(generation)

            assertNull(cache.get(page.key))
            assertEquals(0, cache.stats().entryCount)
        }
    }

    @Test
    fun `pins keep visible entry alive until consumer releases it`() {
        val generation = GenerationId.create()
        val page = page(generation, 0, 1)
        PageCache(PageCachePolicy(page.estimatedCacheBytes)).use { cache ->
            cache.put(page, RenderPriority.CURRENT_VISIBLE)
            cache.updatePins("view", setOf(page.key))
            cache.invalidateGeneration(generation)
            assertEquals(1, cache.stats().entryCount)

            cache.releasePins("view")
            assertEquals(0, cache.stats().entryCount)
        }
    }

    @Test
    fun `concurrent access preserves budget and coherent statistics`() {
        val generation = GenerationId.create()
        val prototype = page(generation, 0, 1)
        val cache = PageCache(PageCachePolicy(prototype.estimatedCacheBytes * 4))
        val start = CountDownLatch(1)
        val workers = (0 until 8).map { worker ->
            thread(start = true) {
                start.await()
                repeat(100) { index ->
                    val candidate = page(generation, (worker + index) % 12, worker + index)
                    cache.put(candidate, RenderPriority.NEIGHBOR)
                    cache.get(candidate.key)
                }
            }
        }
        start.countDown()
        workers.forEach(Thread::join)

        assertTrue(cache.stats().retainedBytes <= cache.maximumBytes)
        assertTrue(cache.stats().entryCount <= 4)
        cache.close()
    }

    @Test
    fun `normalized scale keys coalesce nearby floating zoom values`() {
        val generation = GenerationId.create()

        assertEquals(
            PageRenderKey(generation, 0, RenderScale.normalized(1.249)),
            PageRenderKey(generation, 0, RenderScale.normalized(1.251))
        )
    }

    private fun page(generation: GenerationId, index: Int, value: Int): RenderedPage {
        val bytes = byteArrayOf(
            value.toByte(), 0, 0,
            0, value.toByte(), 0,
            0, 0, value.toByte(),
            value.toByte(), value.toByte(), value.toByte()
        )
        return RenderedPage(
            PageRenderKey(generation, index, RenderScale.DEFAULT),
            RasterImage.owned(2, 2, 6, bytes),
            PageGeometry(2f, 2f),
            "fake",
            "1"
        )
    }
}

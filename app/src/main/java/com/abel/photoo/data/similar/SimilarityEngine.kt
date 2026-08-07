package com.abel.photoo.data.similar

import com.abel.photoo.data.db.PhotoODb
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.SimilarGroup
import com.abel.photoo.model.SimilarityLevel

/**
 * 把一堆哈希聚成"相似组"。
 *
 * 直接两两比较是 O(n²)，两万张照片就是两亿次比较，手机上要跑十几秒。
 * 这里用两层预筛把候选对压到很小的规模：
 *  1. LSH 分桶：64 位 dHash 切成 4 段 16 位，只要有任意一段完全相同就成为候选。
 *     汉明距离 ≤ 12 的两张图，按鸽巢原理至少有一段的差异 ≤ 3，命中率足够高。
 *  2. 时间邻近：连拍照片时间戳挨得很近，10 秒窗口内的照片互为候选。
 * 最后用并查集把候选对连成组。
 */
object SimilarityEngine {

    private const val TIME_WINDOW_MS = 10_000L
    private const val COLOR_TOLERANCE = 110

    fun buildGroups(
        photos: List<PhotoItem>,
        hashes: Map<Long, PhotoODb.HashRow>,
        level: SimilarityLevel,
        strategy: KeepStrategy,
        resolvedKeys: Set<String>,
    ): List<SimilarGroup> {
        val usable = photos.filter { hashes.containsKey(it.id) }
        if (usable.size < 2) return emptyList()

        val indexOf = HashMap<Long, Int>(usable.size * 2)
        usable.forEachIndexed { i, p -> indexOf[p.id] = i }
        val rows = usable.map { hashes.getValue(it.id) }

        val uf = UnionFind(usable.size)
        val pairDistance = HashMap<Long, Int>()

        // ---- 第 1 层：LSH 分桶 ----
        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in rows.indices) {
            for (seg in 0 until 4) {
                val band = ((rows[i].dHash ushr (seg * 16)) and 0xFFFF).toInt()
                val key = band * 4 + seg
                buckets.getOrPut(key) { ArrayList(4) }.add(i)
            }
        }

        fun tryLink(i: Int, j: Int) {
            if (i == j) return
            if (uf.find(i) == uf.find(j)) return
            val a = rows[i]
            val b = rows[j]
            val dd = PerceptualHash.hamming(a.dHash, b.dHash)
            if (dd > level.threshold) return
            val da = PerceptualHash.hamming(a.aHash, b.aHash)
            if (da > level.threshold + 4) return
            if (PerceptualHash.colorDistance(a.avgColor, b.avgColor) > COLOR_TOLERANCE) return
            uf.union(i, j)
            val pk = pairKey(i, j)
            pairDistance[pk] = maxOf(dd, da)
        }

        for (bucket in buckets.values) {
            // 极端情况下（大量纯色截图）单桶会很大，截断保护，避免卡死。
            if (bucket.size < 2 || bucket.size > 400) continue
            for (a in bucket.indices) {
                for (b in a + 1 until bucket.size) tryLink(bucket[a], bucket[b])
            }
        }

        // ---- 第 2 层：时间邻近 ----
        val byTime = usable.indices.sortedBy { usable[it].dateTaken }
        for (p in byTime.indices) {
            val i = byTime[p]
            var q = p + 1
            while (q < byTime.size) {
                val j = byTime[q]
                if (usable[j].dateTaken - usable[i].dateTaken > TIME_WINDOW_MS) break
                tryLink(i, j)
                q++
            }
        }

        // ---- 收敛成组 ----
        val clusters = HashMap<Int, MutableList<Int>>()
        for (i in usable.indices) {
            clusters.getOrPut(uf.find(i)) { ArrayList(2) }.add(i)
        }

        return clusters.values
            .filter { it.size >= 2 }
            .map { members ->
                val items = members.map { usable[it] }.sortedByDescending { it.dateTaken }
                var maxDist = 0
                for (a in members.indices) {
                    for (b in a + 1 until members.size) {
                        pairDistance[pairKey(members[a], members[b])]?.let {
                            if (it > maxDist) maxDist = it
                        }
                    }
                }
                val key = groupKeyOf(items)
                SimilarGroup(
                    key = key,
                    items = items,
                    maxDistance = maxDist,
                    suggestedKeepId = pickKeeper(items, strategy),
                    resolved = key in resolvedKeys,
                )
            }
            .sortedWith(
                compareByDescending<SimilarGroup> { !it.resolved }
                    .thenByDescending { it.reclaimableBytes }
            )
    }

    /** 组的稳定标识：成员 id 排序后拼接的哈希，成员变了 key 就变，决策自动失效。 */
    fun groupKeyOf(items: List<PhotoItem>): String {
        val ids = items.map { it.id }.sorted()
        var h = 1125899906842597L
        for (id in ids) h = h * 31 + id
        return "g${java.lang.Long.toHexString(h)}_${ids.size}"
    }

    fun pickKeeper(items: List<PhotoItem>, strategy: KeepStrategy): Long = when (strategy) {
        KeepStrategy.HIGHEST_RESOLUTION ->
            items.maxWithOrNull(compareBy({ it.pixels }, { it.size }))?.id

        KeepStrategy.LARGEST_FILE ->
            items.maxWithOrNull(compareBy({ it.size }, { it.pixels }))?.id

        KeepStrategy.NEWEST -> items.maxByOrNull { it.dateTaken }?.id
        KeepStrategy.OLDEST -> items.minByOrNull { it.dateTaken }?.id
        KeepStrategy.MANUAL -> items.firstOrNull()?.id
    } ?: items.first().id

    private fun pairKey(i: Int, j: Int): Long {
        val lo = minOf(i, j).toLong()
        val hi = maxOf(i, j).toLong()
        return (hi shl 32) or lo
    }

    /** 路径压缩 + 按秩合并的并查集。 */
    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> {
                    parent[rb] = ra
                    rank[ra]++
                }
            }
        }
    }
}

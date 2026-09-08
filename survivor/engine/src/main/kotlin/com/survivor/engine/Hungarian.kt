package com.survivor.engine

/**
 * Hungarian (Kuhn–Munkres) assignment for a rectangular cost matrix with rows <= cols.
 * Returns, for each row, the column assigned to it. O(rows² · cols).
 */
object Hungarian {
    fun solve(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        if (n == 0) return IntArray(0)
        val m = cost[0].size
        require(n <= m) { "rows ($n) must not exceed cols ($m)" }
        val inf = Double.MAX_VALUE / 4
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { inf }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = inf
                var j1 = 0
                for (j in 1..m) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                        if (minv[j] < delta) { delta = minv[j]; j1 = j }
                    }
                }
                for (j in 0..m) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(n)
        for (j in 1..m) if (p[j] != 0) result[p[j] - 1] = j - 1
        return result
    }
}

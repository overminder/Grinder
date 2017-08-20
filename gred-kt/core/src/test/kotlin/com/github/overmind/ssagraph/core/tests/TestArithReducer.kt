package com.github.overmind.ssagraph.core.tests

import com.github.overmind.ssagraph.core.*
import com.github.overmind.ssagraph.core.arith.*
import org.junit.Assert
import org.junit.Test

class TestArithReducer {
    @Test
    fun testAddOnce() {
        val g = mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add, n1, n2)
            na
        }
        val reduced = ArithReducer.reduce(g, g.retValNode.id) as Reduction.Changed
        Assert.assertEquals(g.nodeAt(reduced.id).op, IntLit(3))
    }

    @Test
    fun testAdd() {
        assertArithReduction(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add, n1, n2)
            na
        }, mkG {
            skipIds(3)
            addNode(IntLit(3))
        })
    }

    @Test
    fun testIf() {
        // Case 1: IfTrue
        assertArithReduction(mkG {
            val n1 = addNode(IntLit(0))
            val n2 = addNode(IntLit(1))
            val n3 = addNode(IntLit(2))
            val nif = addNode(If, n1, n2, n3)
            nif
        }, mkG {
            skipIds(2)
            addNode(IntLit(2))
        })

        // Case 2: IfSame
        assertArithReduction(mkG {
            val n1 = addNode(Argument(0))
            val n2 = addNode(Argument(1))
            val n3 = addNode(Argument(1))
            val nif = addNode(If, n1, n2, n3)
            nif
        }, mkG {
            skipIds(1)
            addNode(Argument(1))
        })
    }

    @Test
    fun testComplexAddIf() {
        assertArithReduction(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add, n1, n2)
            val nt = addNode(Argument(1))
            val nf = addNode(Argument(2))
            val nif = addNode(If, na, nt, nf)
            nif
        }, mkG {
            skipIds(3)
            addNode(Argument(1))
        })
    }

    @Test
    fun testReplaceInput() {
        Assert.assertEquals(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov, n1)
            replaceInput(0, n2, m)
            m
        }, mkG {
            skipIds(1)
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov, n2)
            m
        })
    }

    @Test
    fun testReplace() {
        Assert.assertEquals(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov, n1)
            replace(n1, n2)
            m
        }, mkG {
            skipIds(1)
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov, n2)
            m
        })
    }

    @Test
    fun testCache() {
        mkG {
            val a1 = Argument(0).cacheKey(intArrayOf())
            val a2 = Argument(0).cacheKey(intArrayOf())
            Assert.assertEquals(a1, a2)
            Assert.assertEquals(a1.hashCode(), a2.hashCode())
            val n1 = addNode(Argument(0))
            val n2 = addNode(Argument(0))
            Assert.assertEquals(n1, n2)
            n1
        }
    }

    @Test
    fun testToString() {
        Assert.assertEquals("Node-0<IntLit(value=1)>()", Node(0, IntLit(1)).toStringSimple())
    }

    fun assertArithReduction(g: AGraph, toBe: AGraph) {
        g.edit().reduce(ArithReducer)
        Assert.assertEquals(g, toBe)
    }
}


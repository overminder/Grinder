package com.github.overmind.ssagraph.core.tests

import com.github.overmind.ssagraph.core.*
import com.github.overmind.ssagraph.core.arith.*
import org.junit.Assert
import org.junit.Test

class TestGraphReduction {
    @Test
    fun testReduceArithOnce() {
        val g = mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add)
            addInput(n1, na)
            addInput(n2, na)
            na
        }
        val reduced = ArithOp.Reducer.reduce(g, endUse(g)) as Reduction.Changed
        Assert.assertEquals(g.nodeAt(reduced.id).op, IntLit(3))
    }

    @Test
    fun testReduceArithFully() {
        assertArithReduction(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add)
            addInput(n1, na)
            addInput(n2, na)
            na
        }, mkG {
            skipIds(3)
            addNode(IntLit(3))
        })
    }

    @Test
    fun testReplaceInput() {
        Assert.assertEquals(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov)
            addInput(n1, m)
            replaceInput(0, n2, m)
            m
        }, mkG {
            skipIds(1)
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov)
            addInput(n2, m)
            m
        })
    }

    @Test
    fun testReplace() {
        Assert.assertEquals(mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov)
            addInput(n1, m)
            replace(n1, n2)
            m
        }, mkG {
            skipIds(1)
            val n2 = addNode(IntLit(2))
            val m = addNode(Mov)
            addInput(n2, m)
            m
        })
    }

    @Test
    fun testToString() {
        Assert.assertEquals("Node-0<IntLit(value=1)>()", Node(0, IntLit(1)).toStringSimple())
    }

    fun assertArithReduction(g: AGraph, toBe: AGraph) {
        g.edit().reduce(ArithOp.Reducer)
        Assert.assertEquals(g, toBe)
    }

    fun endUse(g: AGraph): Id {
        return g.retValNode.id
    }
}

fun mkG(block: GraphEditor<ArithOp>.() -> Id): AGraph {
    val g = AGraph()
    val end = g.edit().addNode(End)
    val res = block(g.edit())
    g.edit().addInput(res, end)
    return g
}

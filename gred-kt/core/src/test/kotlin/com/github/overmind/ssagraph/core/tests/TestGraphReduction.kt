package com.github.overmind.ssagraph.core.tests

import com.github.overmind.ssagraph.core.*
import org.junit.Assert
import org.junit.Test

typealias AGraph = Graph<ArithOp>

class TestGraphReduction {
    @Test
    fun testReduceArithOnce() {
        val g = mkG {
            val n1 = newNode(ArithOp.IntLit(1))
            val n2 = newNode(ArithOp.IntLit(2))
            val na = newNode(ArithOp.Add)
            addInput(n1, na)
            addInput(n2, na)
            na
        }
        val reduced = ArithOp.Reducer.reduce(g, endUse(g)) as Reduction.Changed
        Assert.assertEquals(g.nodeAt(reduced.id).op, ArithOp.IntLit(3))
    }

    @Test
    fun testReduceArithFully() {
        assertArithReduction(mkG {
            val n1 = newNode(ArithOp.IntLit(1))
            val n2 = newNode(ArithOp.IntLit(2))
            val na = newNode(ArithOp.Add)
            addInput(n1, na)
            addInput(n2, na)
            na
        }, mkG {
            skipIds(3)
            newNode(ArithOp.IntLit(3))
        })
    }

    @Test
    fun testReplaceInput() {
        Assert.assertEquals(mkG {
            val n1 = newNode(ArithOp.IntLit(1))
            val n2 = newNode(ArithOp.IntLit(2))
            val m = newNode(ArithOp.Mov)
            addInput(n1, m)
            replaceInput(0, n2, m)
            m
        }, mkG {
            skipIds(1)
            val n2 = newNode(ArithOp.IntLit(2))
            val m = newNode(ArithOp.Mov)
            addInput(n2, m)
            m
        })
    }

    @Test
    fun testReplace() {
        Assert.assertEquals(mkG {
            val n1 = newNode(ArithOp.IntLit(1))
            val n2 = newNode(ArithOp.IntLit(2))
            val m = newNode(ArithOp.Mov)
            addInput(n1, m)
            replace(n1, n2)
            m
        }, mkG {
            skipIds(1)
            val n2 = newNode(ArithOp.IntLit(2))
            val m = newNode(ArithOp.Mov)
            addInput(n2, m)
            m
        })
    }

    fun assertArithReduction(g: AGraph, toBe: AGraph) {
        g.edit().reduce(ArithOp.Reducer)
        Assert.assertEquals(g, toBe)
    }

    fun mkG(block: GraphEditor<ArithOp>.() -> Id): AGraph {
        val g = AGraph()
        val end = g.edit().newNode(ArithOp.End)
        val res = block(g.edit())
        g.edit().addInput(res, end)
        return g
    }

    fun endUse(g: AGraph): Id {
        val end = g.findOp(ArithOp.End::class.java).asSequence().single()
        return end.inputs.single()
    }
}
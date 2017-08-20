package com.github.overmind.ssagraph.core.tests

import com.github.overmind.ssagraph.core.Graph
import com.github.overmind.ssagraph.core.arith.*
import org.junit.Assert
import org.junit.Test

class TestArithInterpreter {
    @Test
    fun testAdd() {
        assertInterp(3, mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add, n1, n2)
            na
        })
    }

    @Test
    fun testIf() {
        assertInterp(2, mkG {
            val n1 = addNode(IntLit(0))
            val n2 = addNode(IntLit(1))
            val n3 = addNode(IntLit(2))
            val nif = addNode(If, n1, n2, n3)
            nif
        })
    }

    @Test
    fun testArg() {
        assertInterp(3, mkG {
            val n1 = addNode(Argument(0))
            val n2 = addNode(Argument(1))
            val na = addNode(Add, n1, n2)
            na
        }.copy(argc = 2), 1, 2)
    }

    @Test
    fun testInlineCall() {
        val funcAdd = mkG {
            val n1 = addNode(Argument(0))
            val n2 = addNode(Argument(1))
            addNode(Add, n1, n2)
        }.copy(argc = 2)
        assertInterp(3, mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(InlineCall(funcAdd), n1, n2)
            na
        })
    }

    private fun assertInterp(expected: Int, g: AGraph, vararg args: Int) {
        val interp = ArithInterpreter(g, args)
        Assert.assertEquals(expected, interp.eval().result)

        // 2nd time: run optimized code
        g.edit().reduce(ArithReducer)
        Assert.assertEquals(expected, interp.eval().result)
    }
}
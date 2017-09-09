package com.github.overmind.ssagraph.core.tests

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
            val nif = addNode(Select, n1, n2, n3)
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
            val na = addNode(KnownApply(funcAdd), n1, n2)
            na
        })
    }

    @Test
    fun testFiboRecur() {
        val g = mkG(1) {
            val c1 = addNode(IntLit(1))
            val c2 = addNode(IntLit(2))
            val a1 = addNode(Argument(0))
            val lt = addNode(Lt, a1, c2)
            val sub1 = addNode(Sub, a1, c1)
            val sub2 = addNode(Sub, a1, c2)
            val ifF1 = addNode(KnownApply(g), sub1)
            val ifF2 = addNode(KnownApply(g), sub2)
            val ifF = addNode(Add, ifF1, ifF2)
            addNode(Select, lt, a1, ifF)
        }
        assertInterp(55, g, 10)
    }

    private fun assertInterp(expected: Int, g: AGraph, vararg args: Int) {
        val interp = ArithInterpreter(g, args)
        Assert.assertEquals(expected, interp.eval().result)

        // 2nd time: run optimized code
        g.edit().reduce(ArithReducer)
        Assert.assertEquals(expected, interp.eval().result)
    }
}
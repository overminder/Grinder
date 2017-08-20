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
            val nif = addNode(If, n1, n2, n3)
            nif
        })
    }

    private fun assertInterp(expected: Int, g: AGraph) {
        val interp = ArithInterpreter(g, intArrayOf())
        Assert.assertEquals(expected, interp.eval())
    }
}
package com.github.overmind.ssagraph.core.tests

import com.github.overmind.ssagraph.core.arith.AGraph
import com.github.overmind.ssagraph.core.arith.Add
import com.github.overmind.ssagraph.core.arith.IntLit
import com.github.overmind.ssagraph.core.arith.Interpreter
import org.junit.Assert
import org.junit.Test

class TestInterpreter {
    @Test
    fun interpSimple() {
        val g = mkG {
            val n1 = addNode(IntLit(1))
            val n2 = addNode(IntLit(2))
            val na = addNode(Add)
            addInput(n1, na)
            addInput(n2, na)
            na
        }

        assertInterp(3, g)
    }

    private fun assertInterp(expected: Int, g: AGraph) {
        val interp = Interpreter(g, intArrayOf())
        Assert.assertEquals(expected, interp.eval())
    }
}
package com.github.overmind.graphlib.tests

import com.github.overmind.graphlib.impl.IntGraph
import com.github.overmind.graphlib.impl.IntGraphOps
import org.junit.Assert
import org.junit.Test

class TestConcreteImpl {
    @Test
    fun testToDot() {
        val fst = IntGraph.build {
            val fst = createNode()
            val snd = createNode()
            val trd = createNode()
            fst.successors.add(snd)
            snd.successors.add(trd)
            fst.successors.add(trd)
            fst
        }

        Assert.assertEquals(listOf("digraph IntGraph {", "1 -> 2;", "1 -> 3;", "2 -> 3;", "}")
                .joinToString("\n"),
                IntGraphOps.toDot(fst))
    }
}
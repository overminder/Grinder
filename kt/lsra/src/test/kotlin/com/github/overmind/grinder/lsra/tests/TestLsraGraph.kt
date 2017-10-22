package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.InstrGraph
import com.github.overmind.grinder.asm.Reg
import com.github.overmind.grinder.lsra.AllocationRealizer
import com.github.overmind.grinder.lsra.GraphLiveness
import com.github.overmind.grinder.lsra.LinearScan
import org.junit.Test

class TestLsraGraph {
    val tgl = TestGraphLiveness()

    fun doGraph(g: InstrGraph) {
        val live = GraphLiveness(g)
        tgl.addRaxToLiveOut(live, g.exitBlock)
        live.compute()
        live.fixLiveInOut()

        val lsra = LinearScan(live.liveRanges,listOf(Reg.RDI, Reg.RSI, Reg.RAX))
        lsra.allocate()
        println(live.liveRanges)

        AllocationRealizer(lsra, live.asNumbered()).realize()
        println(g.labelToBlock)
    }

    @Test
    fun straight() {
        val g = tgl.buildStraightGraph()
        doGraph(g)
    }

    @Test
    fun diamond() {
        doGraph(tgl.buildDiamondGraph().toCFG())
    }

    @Test
    fun loop() {
        doGraph(tgl.buildLoopGraph().toCFG())
    }
}
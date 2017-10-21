package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.InstructionBlock
import com.github.overmind.grinder.asm.Reg
import com.github.overmind.grinder.lsra.AllocationRealizer
import com.github.overmind.grinder.lsra.GraphLiveness
import com.github.overmind.grinder.lsra.LinearScan
import org.junit.Test

class TestLsraGraph {
    val tgl = TestGraphLiveness()

    fun doGraph(entry: InstructionBlock, exit: InstructionBlock) {
        val live = GraphLiveness(entry)
        live.computePo()
        tgl.addRaxToLiveOut(live, exit)
        live.compute()
        live.fixLiveInOut(exit)

        val lsra = LinearScan(live.liveRanges,listOf(Reg.RDI, Reg.RSI, Reg.RAX))
        lsra.allocate()
        println(live.liveRanges)

        // val realizer = AllocationRealizer(lsra, b)
    }

    @Test
    fun straight() {
        val (entry, exit) = tgl.buildStraightGraph()
        doGraph(entry, exit)
    }

    @Test
    fun diamond() {
        val g = tgl.buildDiamondGraph()
        doGraph(g.entry, g.exit)
    }

    @Test
    fun loop() {
        val g = tgl.buildLoopGraph()
        doGraph(g.entry, g.exit)
    }
}
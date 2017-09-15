package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.NamedLabel
import com.github.overmind.grinder.asm.Reg
import com.github.overmind.grinder.lsra.GraphLiveness
import org.junit.Assert
import org.junit.Test


class TestGraphLiveness {
    @Test
    fun straight() {
        val v0 = Reg.mkVirtual()

        val exit = buildBlock {
            mov(v0, Reg.RAX)
            ret()
        }

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            jmp(exit.label)
        }.apply { successors = listOf(exit) }

        val live = GraphLiveness(entry)
        live.computePo()
        live.compute()
        Assert.assertEquals(mapOf(entry.label.to(0), exit.label.to(1)), live.labelToRpo)
        Assert.assertEquals(mapOf(entry.label.to(0), exit.label.to(4)), live.labelToInstrOffset)
        println(live.liveRanges)
        println(live.liveOuts)
    }

    @Test
    fun diamond() {
        val exit = buildBlock {
            ret()
        }

        val left = buildBlock {
            jmp(exit.label)
        }.apply { successors = listOf(exit) }

        val right = buildBlock {
            jmp(exit.label)
        }.apply { successors = listOf(exit) }

        val entry = buildBlock {
            je(left.label)
        }.apply { successors = listOf(left, right) }

        val live = GraphLiveness(entry)
        live.computePo()
        Assert.assertEquals(mapOf(entry.label.to(0), right.label.to(1),
                left.label.to(2), exit.label.to(3)), live.labelToRpo)
        Assert.assertEquals(mapOf(entry.label.to(0), right.label.to(2),
                left.label.to(4), exit.label.to(6)), live.labelToInstrOffset)
    }

    @Test
    fun loop() {
        val exit = buildBlock {
            ret()
        }

        val lblLoopEntry = NamedLabel("loopEntry")
        val lblLoopBody = NamedLabel("loopBody")

        val loopBody = buildBlock {
            jmp(lblLoopEntry)
        }

        val loopEntry = buildBlock {
            je(lblLoopBody)
        }.apply { successors = listOf(exit, loopBody) }

        loopBody.successors = listOf(loopEntry)

        val entry = buildBlock {
        }.apply { successors = listOf(loopEntry) }

        val live = GraphLiveness(entry)
        live.computePo()
        Assert.assertEquals(mapOf(entry.label.to(0), loopEntry.label.to(1),
                loopBody.label.to(2), exit.label.to(3)), live.labelToRpo)
        Assert.assertEquals(mapOf(entry.label.to(0), loopEntry.label.to(0),
                loopBody.label.to(2), exit.label.to(4)), live.labelToInstrOffset)
    }
}
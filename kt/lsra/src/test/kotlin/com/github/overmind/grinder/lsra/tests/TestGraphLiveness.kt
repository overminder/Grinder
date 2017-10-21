package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.Imm
import com.github.overmind.grinder.asm.InstructionBlock
import com.github.overmind.grinder.asm.NamedLabel
import com.github.overmind.grinder.asm.Reg
import com.github.overmind.grinder.lsra.GraphLiveness
import org.junit.Assert
import org.junit.Test


class TestGraphLiveness {
    fun buildStraightGraph(): Pair<InstructionBlock, InstructionBlock> {
        val v0 = Reg.mkVirtual()

        val exit = buildBlock {
            mov(v0, Reg.RAX)
            ret()
        }

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            add(v0, v0)
            jmp(exit.label)
        }.apply { successors = listOf(exit) }

        return entry.to(exit)
    }

    interface DiamondGraph {
        val entry: InstructionBlock
        val exit: InstructionBlock
        val left: InstructionBlock
        val right: InstructionBlock
    }

    fun buildDiamondGraph(): DiamondGraph {
        val v0 = Reg.mkVirtual()

        val exit = buildBlock {
            mov(v0, Reg.RAX)
            ret()
        }

        val left = buildBlock {
            add(Imm(1), v0)
            jmp(exit.label)
        }.apply { successors = listOf(exit) }

        val right = buildBlock {
            add(Imm(2), v0)
            jmp(exit.label)
        }.apply { successors = listOf(exit) }

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            je(left.label)
        }.apply { successors = listOf(left, right) }

        return object: DiamondGraph {
            override val exit = exit
            override val entry = entry
            override val left = left
            override val right = right
        }
    }

    interface LoopGraph {
        val entry: InstructionBlock
        val loopHeader: InstructionBlock
        val loopBody: InstructionBlock
        val exit: InstructionBlock
    }

    fun buildLoopGraph(): LoopGraph {
        val v0 = Reg.mkVirtual()

        val exit = buildBlock {
            mov(v0, Reg.RAX)
            ret()
        }

        val lblLoopHeader = NamedLabel("loopHeader")
        val lblLoopBody = NamedLabel("loopBody")

        val loopBody = buildBlock {
            add(Imm(1), v0)
            jmp(lblLoopHeader)
        }

        val loopHeader = buildBlock {
            cmp(Imm(10), v0)
            jl(lblLoopBody)
        }.apply { successors = listOf(exit, loopBody) }

        loopBody.successors = listOf(loopHeader)

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            jmp(lblLoopHeader)
        }.apply { successors = listOf(loopHeader) }

        return object: LoopGraph {
            override val entry = entry
            override val loopHeader = loopHeader
            override val loopBody = loopBody
            override val exit = exit
        }
    }

    fun addRaxToLiveOut(gl: GraphLiveness, exit: InstructionBlock) {
        gl.liveOuts[exit.label] = mutableSetOf(Reg.RAX)
    }

    fun printGL(gl: GraphLiveness) {
        println("lRang: ${gl.liveRanges}")
        println("lOut: ${gl.liveOuts}")
    }

    @Test
    fun straight() {
        val (entry, exit) = buildStraightGraph()

        val live = GraphLiveness(entry)
        live.computePo()
        addRaxToLiveOut(live, exit)
        live.compute()
        live.fixLiveInOut(exit)
        Assert.assertEquals(mapOf(entry.label.to(0), exit.label.to(1)), live.labelToRpo)
        Assert.assertEquals(mapOf(entry.label.to(0), exit.label.to(6)), live.labelToInstrOffset)
        printGL(live)
    }

    @Test
    fun diamond() {
        val g = buildDiamondGraph()

        val live = GraphLiveness(g.entry)
        live.computePo()
        addRaxToLiveOut(live, g.exit)
        live.compute()
        live.fixLiveInOut(g.exit)
        Assert.assertEquals(mapOf(g.entry.label.to(0), g.right.label.to(1),
                g.left.label.to(2), g.exit.label.to(3)), live.labelToRpo)
        Assert.assertEquals(mapOf(g.entry.label.to(0), g.right.label.to(4),
                g.left.label.to(8), g.exit.label.to(12)), live.labelToInstrOffset)
        printGL(live)
    }

    @Test
    fun loop() {
        val g = buildLoopGraph()

        val live = GraphLiveness(g.entry)
        live.computePo()
        addRaxToLiveOut(live, g.exit)
        Assert.assertEquals(mapOf(g.entry.label.to(0), g.loopHeader.label.to(1),
                g.loopBody.label.to(2), g.exit.label.to(3)), live.labelToRpo)
        Assert.assertEquals(mapOf(g.entry.label.to(0), g.loopHeader.label.to(4),
                g.loopBody.label.to(8), g.exit.label.to(12)), live.labelToInstrOffset)
        live.compute()
        live.fixLiveInOut(g.exit)

        printGL(live)
    }
}
package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.*
import com.github.overmind.grinder.lsra.GraphLiveness
import org.junit.Assert
import org.junit.Test


class TestGraphLiveness {
    fun buildStraightGraph(): InstrGraph {
        val v0 = Reg.mkVirtual()

        val exit = buildBlock {
            mov(v0, Reg.RAX)
            ret()
        }

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            add(v0, v0)
            jmp(exit.label)
        }.apply { successors = listOf(exit.label) }

        val g = InstrGraph()
        g.addBlock(entry)
        g.addBlock(exit)
        g.entry = entry.label
        g.exit = exit.label
        g.computePo()
        return g
    }

    interface DiamondGraph {
        val entry: InstructionBlock
        val exit: InstructionBlock
        val left: InstructionBlock
        val right: InstructionBlock

        fun toCFG(): InstrGraph {
            val g = InstrGraph()
            g.addBlock(entry)
            g.addBlock(exit)
            g.addBlock(left)
            g.addBlock(right)
            g.entry = entry.label
            g.exit = exit.label
            g.computePo()
            return g
        }
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
        }.apply { successors = listOf(exit.label) }

        val right = buildBlock {
            add(Imm(2), v0)
            jmp(exit.label)
        }.apply { successors = listOf(exit.label) }

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            je(left.label)
        }.apply { successors = listOf(left.label, right.label) }

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

        fun toCFG(): InstrGraph {
            val g = InstrGraph()
            g.addBlock(entry)
            g.addBlock(exit)
            g.addBlock(loopHeader)
            g.addBlock(loopBody)
            g.entry = entry.label
            g.exit = exit.label
            g.computePo()
            return g
        }
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
        }.apply { successors = listOf(exit.label, loopBody.label) }

        loopBody.successors = listOf(loopHeader.label)

        val entry = buildBlock {
            mov(Reg.RDI, v0)
            jmp(lblLoopHeader)
        }.apply { successors = listOf(loopHeader.label) }

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
        val g = buildStraightGraph()

        val live = GraphLiveness(g)
        addRaxToLiveOut(live, g.exitBlock)
        live.compute()
        live.fixLiveInOut()
        // Assert.assertEquals(mapOf(g.entry!!.to(0), g.exit!!.to(1)), g.labelToRpo)
        Assert.assertEquals(mapOf(g.entry!!.to(0), g.exit!!.to(6)), live.labelToInstrOffset)
        printGL(live)
    }

    @Test
    fun diamond() {
        val g0 = buildDiamondGraph()
        val g = g0.toCFG()

        val live = GraphLiveness(g)
        addRaxToLiveOut(live, g.exitBlock)
        live.compute()
        live.fixLiveInOut()
        // Assert.assertEquals(mapOf(g.entry.label.to(0), g.right.label.to(1),
        //         g.left.label.to(2), g.exit.label.to(3)), live.labelToRpo)
        Assert.assertEquals(mapOf(g0.entry.label.to(0), g0.right.label.to(4),
                g0.left.label.to(8), g0.exit.label.to(12)), live.labelToInstrOffset)
        printGL(live)
    }

    @Test
    fun loop() {
        val g0 = buildLoopGraph()
        val g = g0.toCFG()

        val live = GraphLiveness(g)
        addRaxToLiveOut(live, g.exitBlock)
        // Assert.assertEquals(mapOf(g.entry.label.to(0), g.loopHeader.label.to(1),
        //         g.loopBody.label.to(2), g.exit.label.to(3)), live.labelToRpo)
        live.compute()
        live.fixLiveInOut()
        Assert.assertEquals(mapOf(g0.entry.label.to(0), g0.loopHeader.label.to(4),
                g0.loopBody.label.to(8), g0.exit.label.to(12)), live.labelToInstrOffset)

        printGL(live)
    }
}
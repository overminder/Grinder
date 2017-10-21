package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.*
import com.github.overmind.grinder.asm.tests.AsmTestUtils
import com.github.overmind.grinder.lsra.AllocationRealizer
import com.github.overmind.grinder.lsra.SingleBlockGraphLiveness
import com.github.overmind.grinder.lsra.LinearScan
import org.junit.Assert
import org.junit.Test

private val VERBOSE = false

private fun makeGlobalAndAdjustStack(lsra: LinearScan, b: InstructionBlock): InstructionBlock {
    if (VERBOSE) {
        println("ranges: ${lsra.liveRanges}")
        println("spills: ${lsra.spills}")
        println("reloads: ${lsra.reloads}")
        println(b.renderAtnt())
    }

    assert(b.body.last().op.isRet)
    val slotUsage = lsra.nextSpillSlot
    val newBody = (sequenceOf(AllocationRealizer.mkEnter(slotUsage)) +
            b.body.asSequence().take(b.body.size - 1) +
            sequenceOf(AllocationRealizer.mkLeave(slotUsage)) +
            sequenceOf(b.body.last())).toList()
    return b.copy(NamedLabel(AsmRunner.ENTRY_NAME), global = true, body = newBody)
}

class BlockBuilder(private val instrs: MutableList<Instruction>) {
    private fun emit(op: OpCode) {
        instrs.add(Instruction.of(op))
    }

    private fun emit(op: OpCode, opr: Operand) {
        instrs.add(Instruction.of(op, opr))
    }

    private fun emit(op: OpCode, src: Operand, dst: Operand) {
        instrs.add(Instruction.of(op, src, dst))
    }

    fun mov(src: Operand, dst: Operand) {
        emit(OpCode.MOV, src, dst)
    }

    fun lea(src: Operand, dst: Operand) {
        emit(OpCode.LEA, src, dst)
    }

    fun add(src: Operand, dst: Operand) {
        emit(OpCode.ADD, src, dst)
    }

    fun cmp(src: Operand, dst: Operand) {
        emit(OpCode.CMP, src, dst)
    }

    fun ret() {
        emit(OpCode.RET)
    }

    fun jmp(label: Label) {
        emit(OpCode.JMP, label)
    }

    fun je(label: Label) {
        emit(OpCode.JE, label)
    }

    fun jl(label: Label) {
        emit(OpCode.JL, label)
    }
}

fun buildBlock(run: BlockBuilder.() -> Unit): InstructionBlock {
    val instrs = mutableListOf<Instruction>()
    val builder = BlockBuilder(instrs)
    run(builder)
    val b = InstructionBlock.local(*instrs.toTypedArray())
    return b
}

class TestLsraSingleBlock {
    fun livenessWithRet(b: InstructionBlock): SingleBlockGraphLiveness {
        val live = SingleBlockGraphLiveness(b, setOf(Reg.RAX))
        live.compute()
        return live
    }

    @Test
    fun singleBlockLiveness() {
        val b = buildBlock {
            val v0 = Reg.mkVirtual()
            val v1 = Reg.mkVirtual()
            mov(Reg.RDI, v0)
            mov(Reg.RSI, v1)
            add(v1, v0)
            mov(v0, Reg.RAX)
            ret()
        }

        val live = livenessWithRet(b)
        // println(live.liveRanges)
        Assert.assertEquals(setOf(Reg.RDI, Reg.RSI), live.liveIn)
    }

    @Test
    fun singleBlockAllocateWithoutSpilt() {
        val v0 = Reg.mkVirtual()
        val b = buildBlock {
            mov(Reg.RDI, v0)
            lea(Mem(v0, index = v0), v0)
            add(v0, v0)
            mov(v0, Reg.RAX)
            ret()
        }

        val live = livenessWithRet(b)
        // println(live.liveRanges)

        val lsra = LinearScan(live.liveRanges, listOf(Reg.RDI, Reg.RSI, Reg.RAX))
        lsra.allocate()
        // println(lsra.handled)
        Assert.assertNotNull(live.liveRanges[v0]!!.allocated)
        val realizer = AllocationRealizer(lsra, b)
        val finalB = realizer.realize()
        val finalB2 = makeGlobalAndAdjustStack(lsra, finalB)
        AsmTestUtils.run(finalB2.renderAtnt()) {
            Assert.assertEquals(call(10), 40)
        }
    }

    @Test
    fun singleBlockAllocateWithSpilt() {
        val v0 = Reg.mkVirtual()
        val v1 = Reg.mkVirtual()
        val v2 = Reg.mkVirtual()
        val b = buildBlock {
            mov(Reg.RDI, v0)
            mov(Reg.RDI, v1)
            mov(Reg.RDI, v2)
            add(v1, v0)
            add(v2, v0)
            mov(v0, Reg.RAX)
            ret()
        }

        val live = livenessWithRet(b)
        val lsra = LinearScan(live.liveRanges, listOf(Reg.RDI, Reg.RAX))
        lsra.allocate()
        Assert.assertNotNull(live.liveRanges[v0]!!.allocated)
        Assert.assertNotNull(live.liveRanges[v1]!!.allocated)
        Assert.assertNotNull(live.liveRanges[v2]!!.allocated)

        val realizer = AllocationRealizer(lsra, b)
        val finalB = realizer.realize()
        val finalB2 = makeGlobalAndAdjustStack(lsra, finalB)
        AsmTestUtils.run(finalB2.renderAtnt()) {
            Assert.assertEquals(call(14), 42)
        }
    }
}
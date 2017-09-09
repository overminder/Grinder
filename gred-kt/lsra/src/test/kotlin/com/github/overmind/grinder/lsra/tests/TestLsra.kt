package com.github.overmind.grinder.lsra.tests

import com.github.overmind.grinder.asm.*
import com.github.overmind.grinder.lsra.BlockLiveness
import com.github.overmind.grinder.lsra.LinearScan
import org.junit.Assert
import org.junit.Test

class BlockBuilder(private val instrs: MutableList<Instruction>) {
    private fun emit(op: OpCode) {
        instrs.add(Instruction.of(op))
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

    fun ret() {
        emit(OpCode.RET)
    }
}

class TestLsra {
    fun buildBlock(run: BlockBuilder.() -> Unit): InstructionBlock {
        val instrs = mutableListOf<Instruction>()
        val builder = BlockBuilder(instrs)
        run(builder)
        val b = InstructionBlock.local(*instrs.toTypedArray())
        return b
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

        val live = BlockLiveness(b, setOf(Reg.RAX))
        live.compute()

        println(live.liveRanges)
        Assert.assertEquals(setOf(Reg.RDI, Reg.RSI), live.liveIn)
    }

    @Test
    fun singleBlockAllocateWithoutSpilt() {
        val b = buildBlock {
            val v0 = Reg.mkVirtual()
            // val v1 = Reg.mkVirtual()
            // val v2 = Reg.mkVirtual()
            mov(Reg.RDI, v0)
            // mov(Reg.RDI, v1)
            // mov(Reg.RDI, v2)
            lea(Mem(v0, index = v0), v0)
            // lea(Mem(v0, index = v2), v0)
            mov(v0, Reg.RAX)
            ret()
        }

        val live = BlockLiveness(b, setOf(Reg.RAX))
        live.compute()
        println(live.liveRanges)

        val lsra = LinearScan(live.liveRanges, listOf(Reg.RDI, Reg.RSI, Reg.RAX))
        lsra.allocate()
        println(lsra.handled)
    }
}
package com.github.overmind.grinder.asm.tests

import com.github.overmind.grinder.asm.*
import org.junit.Assert
import org.junit.Test

class TestAsmRunner {
    val ENTRY = NamedLabel(AsmRunner.ENTRY_NAME)

    fun expectResult(source: String, arg: Long, expected: Long) {
        AsmTestUtils.run(source) {
            println("Dylib saved to: $dylibPath")
            val res = call(arg)
            var hasError = false
            try {
                Assert.assertEquals(expected, res)
            } catch (e: Throwable) {
                hasError = true
                throw e
            } finally {
                if (!hasError) {
                    close()
                }
            }
        }
    }

    @Test
    fun testRawX64() {
        val linkageName = AsmRunner.nameForLinkage(AsmRunner.ENTRY_NAME)
        expectResult("""
            .globl $linkageName
            $linkageName:
                leal 1(%edi), %eax
                ret
        """.trimIndent(), 41, 42)
    }

    @Test
    fun testInstructionBlock() {
        val block = InstructionBlock(ENTRY, true, listOf(
                Instruction.of(OpCode.LEA, Mem(Reg.RDI, index = Reg.RDI, disp = 2), Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        expectResult(block.renderAtnt(), 20, 42)
    }

    @Test
    fun testJmp() {
        val lblEnd = NamedLabel("end")
        val start = InstructionBlock(ENTRY, true, listOf(
                Instruction.of(OpCode.JMP, lblEnd)
        ))
        val end = InstructionBlock(lblEnd, body = listOf(
                Instruction.of(OpCode.LEA, Mem(Reg.RDI, index = Reg.RDI, disp = 2), Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        expectResult(InstructionBlocks(listOf(end, start)).renderAtnt(), 20, 42)
    }

    @Test
    fun testJg() {
        val lblT = NamedLabel("t")
        val start = InstructionBlock(ENTRY, true, listOf(
                Instruction.of(OpCode.CMP, Imm(0), Reg.RDI),
                Instruction.of(OpCode.JG, lblT),
                Instruction.of(OpCode.MOV, Imm(0), Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        val t = InstructionBlock(lblT, body = listOf(
                Instruction.of(OpCode.MOV, Imm(1), Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        expectResult(InstructionBlocks(listOf(start, t)).renderAtnt(), 1, 1)
    }

    @Test
    fun testNeg() {
        val block = InstructionBlock(ENTRY, true, listOf(
                Instruction.of(OpCode.NEG, Reg.RDI),
                Instruction.of(OpCode.MOV, Reg.RDI, Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        expectResult(block.renderAtnt(), 20, -20)
    }

    @Test
    fun testFibo() {
        val lblFibo = NamedLabel("fibo")
        val lblRecur = NamedLabel("fiboRecur")
        val start = InstructionBlock(ENTRY, true, listOf(
                Instruction.of(OpCode.JMP, lblFibo)
        ))
        val fiboStart = InstructionBlock(lblFibo, body = listOf(
                Instruction.of(OpCode.CMP, Imm(2), Reg.RDI),
                Instruction.of(OpCode.JGE, lblRecur),
                Instruction.of(OpCode.MOV, Reg.RDI, Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        val fiboRecur = InstructionBlock(lblRecur, body = listOf(
                Instruction.of(OpCode.PUSH, Reg.RDI),
                Instruction.of(OpCode.SUB, Imm(1), Reg.RDI),
                Instruction.of(OpCode.CALL, lblFibo),

                Instruction.of(OpCode.POP, Reg.RDI),
                Instruction.of(OpCode.PUSH, Reg.RAX),
                Instruction.of(OpCode.SUB, Imm(2), Reg.RDI),
                Instruction.of(OpCode.CALL, lblFibo),

                Instruction.of(OpCode.POP, Reg.RDI),
                Instruction.of(OpCode.ADD, Reg.RDI, Reg.RAX),
                Instruction.of(OpCode.RET)
        ))
        expectResult(InstructionBlocks(listOf(start, fiboStart, fiboRecur)).renderAtnt(), 10, 55)
    }
}

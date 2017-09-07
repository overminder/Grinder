package com.github.overmind.grinder.asm

interface AtntSyntax {
    fun renderAtnt(): String
}

data class InstructionBlocks(val bs: List<InstructionBlock>): AtntSyntax {
    override fun renderAtnt(): String {
        return bs.map { it.renderAtnt() }.joinToString("\n")
    }
}

data class InstructionBlock(val name: String, val global: Boolean = false, val body: List<Instruction>): AtntSyntax {
    override fun renderAtnt() = buildString {
        val linkageName = if (global) {
            val name1 = AsmRunner.nameForLinkage(name)
            append("\t.globl $name1\n")
            name1
        } else {
            name
        }
        append("$linkageName:\n")
        append(body.asSequence().map { it.renderAtnt() }.joinToString("\n"))
    }
}

data class Instruction private constructor(val op: OpCode,
                                           val inputs: List<Operand> = emptyList(),
                                           val outputs: List<Operand> = emptyList()): AtntSyntax {
    init {
        op.checkArgs(inputs, outputs)
    }

    private fun allOperands() = (inputs ?: emptyList()) + (outputs ?: emptyList())

    override fun renderAtnt() = buildString {
        append("\t")
        append(op.renderAtnt())
        append("\t")
        allOperands().let {
            append(it.map { it.renderAtnt() }.joinToString(", "))
        }
    }

    companion object {
        fun of(op: OpCode): Instruction {
            assert(op.arity == 0)
            return Instruction(op)
        }

        fun of(op: OpCode, opr: Operand): Instruction {
            assert(op.arity == 1)
            return if (op.inputCount == 1) {
                Instruction(op, listOf(opr))
            } else {
                Instruction(op, outputs = listOf(opr))
            }
        }

        fun of(op: OpCode, src: Operand, dst: Operand): Instruction {
            assert(op.arity == 2)
            return if (op.outputCount == 1) {
                Instruction(op, listOf(src), listOf(dst))
            } else {
                assert(op.outputCount == 0)
                Instruction(op, listOf(src, dst))
            }
        }
    }
}

enum class JmpCondition {
    L,
    Le,
    G,
    Ge,
    E,
    Ne;

    fun inverse() = when (this) {
        L -> Ge
        Le -> G
        G -> Le
        Ge -> L
        E -> Ne
        Ne -> E
    }
}

data class OpCode(val name: String,
                  val inputCount: Int,
                  val outputCount: Int = 0,
                  // Properties
                  val inplace: Boolean = false,
                  val isCall: Boolean = false,
                  val isJmp: Boolean = false,
                  val jmpCondition: JmpCondition? = null,
                  val isRet: Boolean = false): AtntSyntax {
    init {
        if (inplace) {
            assert(inputCount == 2)
            assert(outputCount == 1)
        }
    }

    fun checkArgs(actualInputs: List<Operand>, actualOutputs: List<Operand>) {
        val nInputs = actualInputs.size
        val nOutputs = actualOutputs.size
        if (inplace) {
            assert(nInputs == 1)
            assert(nOutputs == 1)
        } else {
            assert(nInputs == inputCount)
            assert(nOutputs == outputCount)
        }
        if (arity == 2) {
            assert((actualInputs + actualOutputs).any { it is Reg })
        }
    }

    override fun renderAtnt(): String {
        return if (isJmp && jmpCondition != null) {
            "j" + jmpCondition.toString().toLowerCase()
        } else {
            name
        }
    }
    val arity: Int
        get() = inputCount + outputCount - (if (inplace) 1 else 0)

    companion object {
        private val SRC_DST = OpCode("XXX-SRC-DST", 1, 1)

        val MOV = SRC_DST.copy("mov")
        val MOVQ = SRC_DST.copy("movq")
        val LEA = SRC_DST.copy("lea")

        val CMP = OpCode("cmp", 2)

        private val INPLACE = OpCode("XXX-INPLACE", 2, 1, inplace = true)
        val ADD = INPLACE.copy("add")
        val SUB = INPLACE.copy("sub")

        private val J = OpCode("XXX-J", 1, isJmp = true)
        val JMP = J.copy("jmp")
        val JL = J.copy(jmpCondition = JmpCondition.L)
        val JLE = J.copy(jmpCondition = JmpCondition.Le)
        val JG = J.copy(jmpCondition = JmpCondition.G)
        val JGE = J.copy(jmpCondition = JmpCondition.Ge)

        val CALL = OpCode("call", 1, isCall = true)

        val PUSH = OpCode("push", 1)
        val POP = OpCode("pop", 0, 1)

        val RET = OpCode("ret", 0, isRet = true)
    }
}

enum class Scale: AtntSyntax {
    S1,
    S2,
    S4,
    S8;

    override fun renderAtnt() = when (this) {
        S1 -> 1
        S2 -> 2
        S4 -> 4
        S8 -> 8
    }.toString()
}

sealed class Operand: AtntSyntax

data class Imm(val value: Int): Operand() {
    override fun renderAtnt() = "$" + value
}

data class Label(val name: String, val deref: Boolean = false): Operand() {
    // XXX: Two kinds of addressing mode
    override fun renderAtnt() = if (deref) "$" + name else name
}

data class Reg(val id: Int): Operand() {
    override fun renderAtnt(): String = "%" + REG_NAMES[id]

    companion object {
        val RAX = Reg(0)
        val RSI = Reg(6)
        val RDI = Reg(7)
    }

    val isAllocated: Boolean
        get() = id < REG_NAMES.size
}

data class Mem(val base: Int, val disp: Int? = null, val index: Int? = null, val scale: Scale? = null): Operand() {
    override fun renderAtnt() = buildString {
        if (disp != null) {
            append(disp)
        }
        append("(")
        append(Reg(base).renderAtnt())

        if (index != null) {
            append(", ")
            append(Reg(index).renderAtnt())
        }

        if (scale != null) {
            append(", ")
            append(scale.renderAtnt())
        }
        append(")")
    }
}

val REG_NAMES = """
    rax
    rcx
    rdx
    rbx
    rsp
    rbp
    rsi
    rdi

    r8
    r9
    r10
    r11
    r12
    r13
    r14
    r15
""" .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList().apply {
    assert(size == 16)
}

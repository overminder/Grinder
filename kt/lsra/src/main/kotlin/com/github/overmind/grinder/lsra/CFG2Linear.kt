package com.github.overmind.grinder.lsra

import com.github.overmind.grinder.asm.InstrGraph
import com.github.overmind.grinder.asm.InstructionBlock
import com.github.overmind.grinder.asm.Label
import com.github.overmind.grinder.asm.OpCode

// Graph->Trace
// Simply try to arrange blocks to eliminate jmps and make sure cmp/jcc's not-taken branch is arranged immediately
// below the current block.

/*

class CFG2Linear(val live: GraphLiveness) {
    val trace = mutableListOf<Label>()
    val visited = mutableSetOf<Label>()

    val g: InstrGraph
        get() = live.g

    fun run() {
        assert(g.labelPreds[g.entry]?.size ?: 0 == 0)

        trace.add(g.entry)

        val lastInstr = g.entry.body.last()
        val op = lastInstr.op
        when {
            op == OpCode.JMP -> {
                assert(g.entry.successors.size == 1)
                go(g.entry.successors.first())
            }
            op.isJmp && op.jmpCondition != null -> {
                assert(g.entry.successors.size == 2)
                val taken = lastInstr.inputs.first() as Label
                val notTaken = (g.entry.successors - taken).first()
            }
        }
        g.labelPreds
    }

    fun go(b: InstructionBlock) {
        if (b.label in visited) {
            return
        }
    }
}

*/

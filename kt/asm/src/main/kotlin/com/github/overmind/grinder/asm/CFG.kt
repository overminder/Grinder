package com.github.overmind.grinder.asm

class InstrGraph {
    var entry: Label? = null
    var exit: Label? = null

    val labelToBlock = mutableMapOf<Label, InstructionBlock>()

    // RPO
    val poToLabel = mutableListOf<Label>()
    val labelToPo = mutableMapOf<Label, Int>()
    val labelToRpo = mutableMapOf<Label, Int>()
    val labelPreds = mutableMapOf<Label, MutableSet<Label>>()

    val entryBlock: InstructionBlock
        get() = labelToBlock[entry!!]!!

    val exitBlock: InstructionBlock
        get() = labelToBlock[exit!!]!!

    fun addBlock(b: InstructionBlock) {
        labelToBlock[b.label] = b
    }

    fun clearPo() {
        poToLabel.clear()
        labelToPo.clear()
        labelToRpo.clear()
        labelPreds.clear()
    }

    fun computePo() {
        val entry0 = entry ?: throw RuntimeException("No entry")
        clearPo()

        val visited = mutableSetOf<Label>()
        fun dfs(b: InstructionBlock) {
            if (visited.add(b.label)) {
                b.successors.forEach {
                    dfs(labelToBlock[it]!!)
                    // Also fill the predMap
                    labelPreds.compute(it) { _, ps ->
                        (ps ?: mutableSetOf()).apply {
                            add(b.label)
                        }
                    }
                }
                poToLabel += b.label
            }
        }

        dfs(labelToBlock[entry0]!!)
        val numBlocks = poToLabel.size
        poToLabel.reversed().forEachIndexed { ix, lbl ->
            labelToRpo[lbl] = ix
            labelToPo[lbl] = numBlocks - ix - 1
        }
    }
}

// Just for rendering.
data class InstructionBlocks(val bs: List<InstructionBlock>): AtntSyntax {
    override fun renderAtntWithDef(def: AtntSyntaxDef): String {
        return bs.joinToString("\n") { it.renderAtntWithDef(def) }
    }
}

data class InstructionBlock(val label: Label,
                            val global: Boolean = false,
                            val body: List<Instruction>,
                            var successors: List<Label> = emptyList()): AtntSyntax {
    override fun toString() = renderAtntWithDef(RelaxedAtntSyntaxDef)

    override fun renderAtntWithDef(def: AtntSyntaxDef) = buildString {
        val linkageName = if (global) {
            val name1 = AsmRunner.nameForLinkage(label.renderAtntWithDef(def))
            append("\t.globl $name1\n")
            name1
        } else {
            label.renderAtntWithDef(def)
        }
        append("$linkageName:\n")
        append("# -> ${successors.map { it }.joinToString(", ")}\n")
        append(body.asSequence().map { it.renderAtntWithDef(def) }.joinToString("\n"))
    }

    companion object {
        fun local(vararg body: Instruction): InstructionBlock {
            return InstructionBlock(LocalLabel.mkUnique(), body = body.toList())
        }
    }
}
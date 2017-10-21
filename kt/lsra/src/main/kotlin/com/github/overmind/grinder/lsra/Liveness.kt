package com.github.overmind.grinder.lsra

import com.github.overmind.grinder.asm.*
import java.util.*

class GraphLiveness(val entry: InstructionBlock) {
    val poToLabel = mutableListOf<Label>()
    val labelToBlock = mutableMapOf<Label, InstructionBlock>()
    val labelToPo = mutableMapOf<Label, Int>()
    val labelToRpo = mutableMapOf<Label, Int>()
    val liveIns = mutableMapOf<Label, Set<Reg>>()
    val liveOuts = mutableMapOf<Label, MutableSet<Reg>>()
    val labelToInstrOffset = mutableMapOf<Label, Int>()
    val labelPreds = mutableMapOf<Label, MutableSet<Label>>()
    private val data = LivenessData()

    val liveRanges
        get() = data.liveRanges

    fun computePo() {
        val visited = mutableSetOf<Label>()
        fun dfs(b: InstructionBlock) {
            if (visited.add(b.label)) {
                b.successors.forEach {
                    dfs(it)
                    // Also fill the predMap
                    labelPreds.compute(it.label) { _, ps ->
                        (ps ?: mutableSetOf()).apply {
                            add(b.label)
                        }
                    }
                }
                poToLabel += b.label
                labelToBlock[b.label] = b
            }
        }

        dfs(entry)
        val numBlocks = poToLabel.size
        var prevInstrIxOffset = 0
        poToLabel.reversed().forEachIndexed { ix, lbl ->
            labelToRpo[lbl] = ix
            labelToPo[lbl] = numBlocks - ix - 1

            // Also count the instr indices.
            labelToInstrOffset[lbl] = prevInstrIxOffset
            prevInstrIxOffset += UsePosition.positionCountFromInstrCount(labelToBlock[lbl]!!.body.size)
        }
    }

    fun compute() {
        // The underlying LinkedHashMap is FIFO
        val workQ = mutableSetOf<Label>()
        workQ.addAll(poToLabel)

        while (workQ.isNotEmpty()) {
            val lbl = workQ.first()
            workQ.remove(lbl)

            val b = labelToBlock[lbl]!!
            val liveOut = liveOuts.compute(lbl) { _, rs -> rs ?: mutableSetOf() }!!
            val bLive = BlockLiveness(b, liveOut, labelToInstrOffset[lbl]!!, data)
            bLive.compute()
            val liveIn = bLive.liveIn
            liveIns[b.label] = liveIn

            // println("g.compute(${lbl})")

            labelPreds[lbl]?.let { preds ->
                preds.forEach {
                    if (liveIn != liveOuts[it]) {
                        // liveIn changed for this block: enqueue
                        liveOuts[it] = liveIn
                        workQ += it
                    }
                }
            }
        }

        data.linkLiveRanges()
    }

    fun fixLiveInOut(exit: InstructionBlock) {
        fixLiveInOut0(liveRanges, liveBefore = liveIns[entry.label])
        fixLiveInOut0(liveRanges, liveAfter = liveOuts[exit.label])
    }
}

internal class LivenessData {
    val usePos = mutableMapOf<Reg, MutableList<UsePosition>>()
    val liveRanges = mutableMapOf<Reg, LiveRange>()

    fun linkLiveRanges() {
        usePos.forEach { r, poses ->
            val sorted = poses.sortedWith(UsePosition.ORDER_BY_IX_AND_KIND)
            val rg = LiveRange(sorted.toMutableList())
            if (r.isPhysical) {
                rg.allocated = r
            }
            liveRanges[r] = rg
        }
    }
}

internal class BlockLiveness(val block: InstructionBlock,
                             liveOut: Set<Reg>,
                             val instrIxOffset: Int,
                             val data: LivenessData) {
    private val liveNow = liveOut.toMutableSet()
    val liveIn = mutableSetOf<Reg>()

    fun addOprDef(opr: Operand, pos: Int, oprIx: OperandIx) {
        when (opr) {
            is Reg -> addRegDef(opr, pos, oprIx)
            is Mem -> opr.regs().forEach { addRegUse(it, pos, oprIx) }
            else -> {}
        }
    }

    fun addRegDef(r: Reg, pos: Int, oprIx: OperandIx) {
        if (liveNow.remove(r)) {
            addUsePos(UsePosition(r, pos, UsePosition.Kind.Write, oprIx))
        }
    }

    fun addUsePos(upos: UsePosition) {
        data.usePos.compute(upos.reg) { _, v0 ->
            val v = v0 ?: mutableListOf()
            v += upos
            v
        }
    }

    fun addOprUse(opr: Operand, pos: Int, oprIx: OperandIx) {
        when (opr) {
            is Reg -> addRegUse(opr, pos, oprIx)
            is Mem -> opr.regs().forEach { addRegUse(it, pos, oprIx) }
            else -> {}
        }
    }

    fun addRegUse(r: Reg, pos: Int, oprIx: OperandIx) {
        if (liveNow.add(r)) {
            addUsePos(UsePosition(r, pos, UsePosition.Kind.Read, oprIx))
        }
    }

    fun addInplaceOprUse(opr: Operand, pos: Int, oprIx: OperandIx) {
        when (opr) {
            is Reg -> addInplaceRegUse(opr, pos, oprIx)
            is Mem -> opr.regs().forEach { addRegUse(it, pos, oprIx) }
            else -> {}
        }
    }

    fun addInplaceRegUse(r: Reg, pos: Int, oprIx: OperandIx) {
        // An inplace use doesn't change the liveness.
        // assert(r in liveNow)

        // Note that in cyclic graphs, the predecessors of the current block might have not yet been visited.
        // In this case, liveIn will be empty and we guard against this situation.
        // It's safe to do so as once we start to visit the predecessors, their liveOuts will change and therefore
        // this block will be computed again.
        if (r in liveNow) {
            addUsePos(UsePosition(r, pos, UsePosition.Kind.ReadWrite, oprIx))
        }
    }

    fun compute() {
        block.body.withIndex().reversed().forEach { (rawIx, instr) ->
            val pos = instrIxOffset + UsePosition.positionCountFromInstrCount(rawIx)
            instr.outputs.forEachIndexed { outputIx, it ->
                addOprDef(it, pos, OperandIx(outputIx, isInput = false))
            }
            instr.inputs.forEachIndexed { inputIx, opr ->
                val oprIx = OperandIx(inputIx, isInput = true)
                if (inputIx == instr.inputs.size - 1 && instr.op.sameAsLastInput) {
                    // Output same as last input: inplace use
                    addInplaceOprUse(opr, pos, oprIx)
                } else {
                    addOprUse(opr, pos, oprIx)
                }
            }
        }
        liveIn.addAll(liveNow)
        data.linkLiveRanges()
    }

    fun verify() {
        data.liveRanges.forEach { _, u -> u.verify() }
    }
}

// Normalize the UsePositions for the given live-before and live-after registers so that they
// form valid LiveRanges (rather than a single degenerated UsePosition).
// XXX: This is correct for graphs with a single exit. What about multi-exit graphs? We might still need to
// special-case that...
private fun fixLiveInOut0(liveRanges: LiveRangeMap, liveBefore: Set<Reg>? = null, liveAfter: Set<Reg>? = null) {
    liveBefore?.let {
        it.forEach {
            // assert(it.isPhysical)
            liveRanges[it]!!.let { rg ->
                val posBefore = UsePosition(it, Int.MIN_VALUE, UsePosition.Kind.Write, OperandIx.DUMMY)
                rg.poses.add(0, posBefore)
            }
        }
    }
    liveAfter?.let {
        it.forEach {
            // assert(it.isPhysical)
            liveRanges[it]!!.let { rg ->
                val posAfter = UsePosition(it, Int.MAX_VALUE, UsePosition.Kind.Read, OperandIx.DUMMY)
                rg.poses.add(posAfter)
            }
        }
    }
}


// This adds artificial live ranges for pre-block liveIns and post-block liveOuts.
class SingleBlockGraphLiveness(block: InstructionBlock, val liveOut: Set<Reg>) {
    private val inner = BlockLiveness(block, liveOut, 0, LivenessData())

    val liveRanges
        get() = inner.data.liveRanges

    val liveIn
        get() = inner.liveIn

    fun compute() {
        inner.compute()
        fixLiveInOut0(liveRanges, liveIn, liveOut)
        inner.verify()
    }
}

data class UsePosition(val reg: Reg, val instrIx: Int, val kind: Kind, val operandIx: OperandIx) {
    enum class Kind {
        Read,
        ReadWrite,
        Write;

        val hasRead: Boolean
            get() = this != Kind.Write

        val hasWrite: Boolean
            get() = this != Kind.Read

        // Used to further order an UsePosition when LSRAing.
        val useOrder: Int
            get() = when (this) {
                Read -> 0
                else -> 1
            }
    }

    val read: Boolean
        get() = kind.hasRead

    val write: Boolean
        get() = kind.hasWrite

    override fun toString(): String {
        val r = if (read) "R" else ""
        val w = if (write) "W" else ""
        return "[$instrIx] $reg $r$w"
    }

    companion object {
        val ORDER_BY_IX_AND_KIND: Comparator<UsePosition>
            get() = compareBy<UsePosition> {
                it.instrIx
            }.thenBy {
                it.kind.useOrder
            }

        fun isGapPosition(instrIx: Int): Boolean {
            return instrIx % 2 == 1
        }

        fun toGapBefore(instrIx: Int): Int {
            return if (isGapPosition(instrIx)) {
                instrIx
            } else {
                instrIx - 1
            }
        }

        fun positionCountFromInstrCount(instrCount: Int) = instrCount * 2
    }
}

data class LiveRange(var poses: MutableList<UsePosition>, var allocated: Reg? = null,
                     val children: MutableList<LiveRange> = mutableListOf(),
                     val parent: LiveRange? = null) {
    override fun toString(): String {
        return "LiveRange($poses, allocated = $allocated, parent = $parent)"
    }

    fun verify() {
        assert(poses.size >= 2)
    }

    val firstUsePosition: UsePosition
        get() = poses.first()

    val lastUsePosition: UsePosition
        get() = poses.last()

    val virtualReg: Reg
        get() = firstUsePosition.reg

    val groupByRealLiveness: List<LivenessGroup>
        get() {
            // println("gBRL: $this")
            val res = mutableListOf<LivenessGroup>()
            var from: Int? = null
            var to: Int? = null
            poses.forEachIndexed { ix, pos ->
                if (pos.write) {
                    if (pos.read) {
                        // Inplace: continue
                        to = ix
                    } else {
                        // New def: commit current
                        if (from == null) {
                            // Entirely new.
                            from = ix
                        } else {
                            assert(to != null)
                            res += LivenessGroup(from!!, to!!, this)
                            from = ix
                        }
                    }
                } else {
                    assert(pos.read)
                    // Read only: continue
                    to = ix
                }
            }
            if (from != null) {
                res += LivenessGroup(from!!, to!!, this)
            }
            return res
        }


    companion object {
        val DUMMY = LiveRange(mutableListOf(
                UsePosition(Reg.RAX, -1, UsePosition.Kind.Write, OperandIx.DUMMY),
                UsePosition(Reg.RAX, -1, UsePosition.Kind.Read, OperandIx.DUMMY)
        ))
    }

    operator fun contains(other: UsePosition): Boolean {
        groupByRealLiveness.forEach {
            val from = it.from
            val to = it.to
            return if (from.instrIx < other.instrIx && other.instrIx < to.instrIx) {
                true
            } else if (other.instrIx == to.instrIx) {
                assert(to.read)
                assert(other.write && !other.read)
                true
            } else {
                false
            }
        }
        return false
    }

    fun endsBefore(otherIx: Int): Boolean {
        // Can be = as other.lastUse is always a read, which will not interfere with currentPos.
        // XXX: What if other needs to be reloaded?
        return lastUsePosition.instrIx <= otherIx
    }

    fun endsBefore(other: UsePosition): Boolean {
        return endsBefore(other.instrIx)
    }

    fun intersects(other: LiveRange): Boolean {
        return nextIntersection(other) != null
    }

    fun nextIntersection(other: LiveRange): Int? {
        val myGroups = groupByRealLiveness
        val otherGroups = other.groupByRealLiveness

        myGroups.forEach {
            val from = it.from
            val to = it.to
            otherGroups.forEach {
                if (to.instrIx <= it.from.instrIx || it.to.instrIx <= from.instrIx) {
                    // No intersection
                } else {
                    return listOf(from.instrIx, to.instrIx, it.from.instrIx, it.to.instrIx).sorted()[1]
                }
            }
        }
        return null
    }

    fun nextPositionAfter(other: UsePosition) = nextPositionAfter(other.instrIx)

    fun nextPositionAfter(instrIx: Int): UsePosition? {
        return poses.firstOrNull { instrIx < it.instrIx }
    }

    fun nextListIxAfter(instrIx: Int): Int {
        return poses.indexOfFirst { instrIx < it.instrIx }
    }

    fun groupContains(instrIx: Int): Boolean {
        var afterPrevious = false
        groupByRealLiveness.forEach {
            if (afterPrevious && instrIx < it.from.instrIx) {
                // After previous and before current: falls between two groups.
                return false
            }
            if (it.from.instrIx < instrIx && instrIx < it.to.instrIx) {
                return true
            }
            afterPrevious = it.to.instrIx < instrIx
        }
        assert(false) { "No intersection" }
        return false
    }

    fun findRangeWithPos(instrIx: Int): LiveRange? {
        return if (poses.find { it.instrIx == instrIx } != null) {
            this
        } else {
            children.asSequence().mapNotNull {
                it.findRangeWithPos(instrIx)
            }.firstOrNull()
        }
    }
}

data class LivenessGroup(val fromListIx: Int, val toListIx: Int, val belongsTo: LiveRange) {
    val from
        get() = belongsTo.poses[fromListIx]

    val to
        get() = belongsTo.poses[toListIx]
}

typealias LiveRangeMap = Map<Reg, LiveRange>

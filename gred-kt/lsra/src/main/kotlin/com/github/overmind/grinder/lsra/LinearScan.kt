package com.github.overmind.grinder.lsra

import com.github.overmind.grinder.asm.*

class BlockLiveness(val block: InstructionBlock, val liveOut: Set<Reg>) {
    val nPos = block.body.size * 2

    private val liveNow = liveOut.toMutableSet()
    val liveIn = mutableSetOf<Reg>()
    private val usePos = mutableMapOf<Reg, MutableList<UsePosition>>()
    val liveRanges = mutableMapOf<Reg, LiveRange>()

    fun addDef(opr: Operand, pos: Int) {
        when (opr) {
            is Reg -> addDef(opr, pos)
            is Mem -> opr.regs().forEach { addUse(it, pos) }
        }
    }

    fun addDef(r: Reg, pos: Int) {
        if (liveNow.remove(r)) {
            addUsePos(UsePosition(r, pos, UsePosition.Kind.Write))
        }
    }

    fun addUsePos(upos: UsePosition) {
        usePos.compute(upos.reg) { _, v0 ->
            val v = v0 ?: mutableListOf()
            v += upos
            v
        }
    }

    fun addUse(opr: Operand, pos: Int) {
        when (opr) {
            is Reg -> addUse(opr, pos)
            is Mem -> opr.regs().forEach { addUse(it, pos) }
        }
    }

    fun addUse(r: Reg, pos: Int) {
        if (liveNow.add(r)) {
            addUsePos(UsePosition(r, pos, UsePosition.Kind.Read))
        }
    }

    fun addInplaceUse(opr: Operand, pos: Int) {
        when (opr) {
            is Reg -> addInplaceUse(opr, pos)
            is Mem -> opr.regs().forEach { addUse(it, pos) }
        }
    }

    fun addInplaceUse(r: Reg, pos: Int) {
        liveNow.add(r)
        addUsePos(UsePosition(r, pos, UsePosition.Kind.ReadWrite))
    }

    fun compute() {
        block.body.withIndex().reversed().forEach { (rawIx, instr) ->
            val pos = rawIx * 2
            instr.outputs.forEach {
                addDef(it, pos)
            }
            instr.inputs.forEachIndexed { ix, opr ->
                if (ix == instr.inputs.size - 1 && instr.op.sameAsLastInput) {
                    // Same as last input: inplace use
                    addInplaceUse(opr, pos)
                } else {
                    addUse(opr, pos)
                }
            }
        }
        liveIn.addAll(liveNow)
        linkLiveRange()

        fixLiveInOut()
        verify()
    }

    private fun linkLiveRange() {
        usePos.forEach { r, poses ->
            val sorted = poses.sortedWith(UsePosition.ORDER_BY_IX_AND_KIND)
            val rg = LiveRange(sorted)
            if (r.isAllocated) {
                rg.allocated = r
            }
            liveRanges[r] = rg
        }
    }

    fun fixLiveInOut() {
        liveIn.forEach {
            assert(it.isAllocated)
            liveRanges.compute(it) { r: Reg, rg0: LiveRange? ->
                val rg = rg0!!
                val posBefore = UsePosition(r, Int.MIN_VALUE, UsePosition.Kind.Write)
                rg.copy(poses = listOf(posBefore) + rg.poses)
            }
        }
        liveOut.forEach {
            assert(it.isAllocated)
            liveRanges.compute(it) { r: Reg, rg0: LiveRange? ->
                val rg = rg0!!
                val posAfter = UsePosition(r, Int.MAX_VALUE, UsePosition.Kind.Read)
                rg.copy(poses = rg.poses + listOf(posAfter))
            }
        }
    }

    fun verify() {
        liveRanges.forEach { _, u -> u.verify() }
    }
}

data class UsePosition(val reg: Reg, val ix: Int, val kind: Kind) {
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
        return "[$ix] $reg $r$w"
    }

    companion object {
        val ORDER_BY_IX_AND_KIND: Comparator<UsePosition>
            get() = compareBy<UsePosition> {
                it.ix
            }.thenBy {
                it.kind.useOrder
            }
    }
}

data class LiveRange(val poses: List<UsePosition>, var allocated: Reg? = null) {
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
        val DUMMY = LiveRange(listOf(
                UsePosition(Reg.RAX, -1, kind = UsePosition.Kind.Write),
                UsePosition(Reg.RAX, -1, kind = UsePosition.Kind.Read)
        ))
    }

    operator fun contains(other: UsePosition): Boolean {
        groupByRealLiveness.forEach {
            val from = it.from
            val to = it.to
            return if (from.ix < other.ix && other.ix < to.ix) {
                true
            } else if (other.ix == to.ix) {
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
        return lastUsePosition.ix <= otherIx
    }

    fun endsBefore(other: UsePosition): Boolean {
        return endsBefore(other.ix)
    }

    fun firstPosOfNextIntersection(other: LiveRange): Int? {
        val myGroups = groupByRealLiveness
        val otherGroups = other.groupByRealLiveness

        myGroups.forEach {
            val from = it.from
            val to = it.to
            otherGroups.forEach {
                if (to.ix <= it.from.ix || it.to.ix <= from.ix) {
                    // No intersection
                } else {
                    return listOf(from.ix, to.ix, it.from.ix, it.to.ix).sorted()[1]
                }
            }
        }
        return null
    }
}

data class LivenessGroup(val fromIx: Int, val toIx: Int, val belongsTo: LiveRange) {
    val from
        get() = belongsTo.poses[fromIx]

    val to
        get() = belongsTo.poses[toIx]
}

typealias LiveRangeMap = Map<Reg, LiveRange>

class LinearScan(liveRanges: LiveRangeMap, val physicalRegs: List<Reg>) {
    val unhandled: MutableList<LiveRange>
    val fixed: List<LiveRange>
    val active = mutableListOf<LiveRange>()
    val inactive = mutableListOf<LiveRange>()
    val handled = mutableListOf<LiveRange>()
    var current = LiveRange.DUMMY

    val currentPosition
        get() = current.firstUsePosition

    init {
        val sorted = liveRanges.values.sortedByDescending { it.firstUsePosition.ix }
        fixed = sorted.filter { it.allocated != null }
        unhandled = sorted.filter { it.allocated == null }.toMutableList()
    }

    fun allocate() {
        while (unhandled.isNotEmpty()) {
            current = unhandled.pop()

            checkActiveInactive(fromActive = true)
            checkActiveInactive(fromActive = false)

            if (!tryAllocateFree()) {
                assert(false) {"allocateBlockedReg" }
            }
            // If current has an allocated reg: add to active
            if (current.allocated != null) {
                active += current
            }
        }
        handled.addAll(inactive)
        handled.addAll(active)
        inactive.clear()
        active.clear()
    }

    private fun tryAllocateFree(): Boolean {
        val regsFreeUntil = physicalRegs.map {
            it.to(Int.MAX_VALUE)
        }.toMap(mutableMapOf())

        active.forEach {
            regsFreeUntil[it.allocated!!] = 0
        }

        inactive.forEach {
            val intersection = it.firstPosOfNextIntersection(current)!!
            regsFreeUntil[it.allocated!!] = intersection
        }

        fixed.forEach {
            val intersection = it.firstPosOfNextIntersection(current)
            if (intersection != null) {
                regsFreeUntil[it.allocated!!] = intersection
            }
        }

        val mostFreeReg = regsFreeUntil.maxBy { it.value }!!
        return when {
            mostFreeReg.value == 0 -> {
                // Not free.
                false
            }
            current.endsBefore(mostFreeReg.value) -> {
                // Full allocation
                current.allocated = mostFreeReg.key
                true
            }
            else -> {
                // Partial allocation
                current.allocated = mostFreeReg.key
                assert(false) { "Partial allocation" }
                true
            }
        }
    }

    private fun checkActiveInactive(fromActive: Boolean) {
        val (from, to) = if (fromActive) {
            active.to(inactive)
        } else {
            inactive.to(active)
        }

        var ix = 0
        while (ix < from.size) {
            val it = from[ix]
            when {
                it.endsBefore(currentPosition) -> {
                    from.swapPop(ix)
                    handled += it
                }
                it.contains(currentPosition) && !fromActive -> {
                    from.swapPop(ix)
                    to += it
                }
                // ^ The above two situations don't need an increment in ix.
                else -> ix++
            }
        }
    }
}

private fun <A> MutableList<A>.pop(): A {
    val res = last()
    removeAt(size - 1)
    return res
}

private fun <A> MutableList<A>.swapPop(ix: Int): A {
    val res = this[ix]
    this[ix] = this[size - 1]
    pop()
    return res
}

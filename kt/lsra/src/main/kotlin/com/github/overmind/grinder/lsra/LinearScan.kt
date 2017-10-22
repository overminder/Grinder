package com.github.overmind.grinder.lsra

import com.github.overmind.grinder.asm.*

class LinearScan(val liveRanges: LiveRangeMap, val physicalRegs: List<Reg>) {
    val unhandled: MutableList<LiveRange>
    val fixed: List<LiveRange>
    val active = mutableListOf<LiveRange>()
    val inactive = mutableListOf<LiveRange>()
    val handled = mutableListOf<LiveRange>()
    var current = LiveRange.DUMMY

    val currentPosition
        get() = current.firstUsePosition

    val spills = mutableMapOf<Int, MutableSet<SpillReloadPair>>()
    // | Note that we don't actually need the previously used physical reg in reloadMap.
    // Still we preserve the duality for easier coding.
    val reloads = mutableMapOf<Int, MutableSet<SpillReloadPair>>()
    val allocatedSpillSlot = mutableMapOf<Reg, Int>()
    var nextSpillSlot = 0

    init {
        val sorted = liveRanges.values.sortedByDescending { it.firstUsePosition.instrIx }
        fixed = sorted.filter { it.allocated != null }
        unhandled = sorted.filter { it.allocated == null }.toMutableList()
    }

    fun allocate() {
        while (unhandled.isNotEmpty()) {
            current = unhandled.pop()

            checkActiveInactive(fromActive = true)
            checkActiveInactive(fromActive = false)

            if (!tryAllocateFree()) {
                allocateBlocked()
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
            val intersection = it.nextIntersection(current)!!
            regsFreeUntil[it.allocated!!] = intersection
        }

        fixed.forEach {
            it.nextIntersection(current)?.let { sect ->
                regsFreeUntil[it.allocated!!] = sect
            }
        }

        val farthestUse = regsFreeUntil.maxBy { it.value }!!
        return when {
            farthestUse.value <= currentPosition.instrIx -> {
                // Not free.
                false
            }
            current.endsBefore(farthestUse.value) -> {
                // Full allocation
                current.allocated = farthestUse.key
                true
            }
            else -> {
                // Partial allocation
                current.allocated = farthestUse.key
                println("Partial alloc: $current on ${farthestUse.toPair()}, freeUntil = $regsFreeUntil")
                splitBefore(current, farthestUse.value)
                true
            }
        }
    }

    private fun splitBefore(victim: LiveRange, instrIx: Int) {
        val rest = victim.splitBefore(instrIx, spillReloadBuilder())
        unhandled += rest
    }

    private fun allocateBlocked() {
        val regNextUse: MutableMap<Reg, Pair<Int, LiveRange?>> = physicalRegs.map {
            it.to(Int.MAX_VALUE.to(null))
        }.toMap(mutableMapOf())

        active.forEach {
            regNextUse[it.allocated!!] = it.nextPositionAfter(currentPosition)!!.instrIx.to(it)
        }

        inactive.forEach {
            if (it.intersects(current)) {
                regNextUse[it.allocated!!] = it.nextPositionAfter(currentPosition)!!.instrIx.to(it)
            }
        }

        fixed.forEach {
            it.nextIntersection(current)?.let { sect ->
                regNextUse[it.allocated!!] = sect.to(it)
            }
        }

        val farthestUse = regNextUse.maxBy { it.value.first }!!
        if (farthestUse.value.first <= currentPosition.instrIx) {
            // Split self
            assert(false) { "Split self($current): not implemented" }
        } else {
            // Split farthest
            val victim = farthestUse.value.second!!
            splitBefore(victim, currentPosition.instrIx)
            current.allocated = victim.allocated
            active += current
        }
    }

    private fun spillReloadBuilder() = object: SpillReloadBuilder {
        override fun addSpill(instrIx: Int, regPair: SpillReloadPair) {
            allocatedSpillSlot.compute(regPair.virtual) { _, slot0 ->
                val slot = slot0 ?: nextSpillSlot++
                spills.compute(instrIx) { _, regsToSpill ->
                    (regsToSpill ?: mutableSetOf()).apply {
                        add(regPair)
                    }
                }
                slot
            }
        }

        override fun addReload(instrIx: Int, regPair: SpillReloadPair) {
            reloads.compute(instrIx) { _, regsToReload ->
                (regsToReload ?: mutableSetOf()).apply {
                    add(regPair)
                }
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
                // ^ The above two situations don't need an increment in instrIx.
                else -> ix++
            }
        }
    }
}

class AllocationRealizer(val lsra: LinearScan, val instrss: List<NumberedInstructions>) {
    companion object {
        val STACK_SLOT_SIZE = 8
        val SP = Reg.RSP

        fun slotToOperand(slotIx: Int): Mem {
            return Mem(SP, STACK_SLOT_SIZE * slotIx)
        }

        fun mkEnter(slotUsage: Int): Instruction {
            return Instruction.of(OpCode.SUB, Imm(STACK_SLOT_SIZE * slotUsage), SP)
        }

        fun mkLeave(slotUsage: Int): Instruction {
            return Instruction.of(OpCode.ADD, Imm(STACK_SLOT_SIZE * slotUsage), SP)
        }
    }

    private fun realizeSpillReload(isSpill: Boolean, instrIx: Int, out: MutableList<Instruction>) {
        val from = if (isSpill) {
            lsra.spills
        } else {
            lsra.reloads
        }
        from[instrIx]?.let {
            it.forEach {
                val slotIx = lsra.allocatedSpillSlot[it.virtual]!!
                val stackSlot = slotToOperand(slotIx)
                out += if (isSpill) {
                    Instruction.of(OpCode.MOV, it.physical, stackSlot)
                } else {
                    Instruction.of(OpCode.MOV, stackSlot, findAllocatedRegAt(instrIx, it.virtual))
                }
            }
        }
    }

    private fun findAllocatedRegAt(instrIx: Int, r: Reg): Reg {
        return if (r.isVirtual) {
            val rg = lsra.liveRanges[r]!!.findRangeWithPos(instrIx)!!
            rg.allocated!!
        } else {
            r
        }
    }

    private fun replaceRegInInstr(instrIx: Int, instr: Instruction): Instruction {
        var out = instr
        instr.operandIxs.forEach {
            out = out.mapRegAt(it) { findAllocatedRegAt(instrIx, it) }
        }
        return out
    }

    fun realize() {
        instrss.forEach {
            val out = mutableListOf<Instruction>()
            it.iterator().forEach { (instrIx, instr) ->
                // | Originally we are checking if this is the first instr.
                if (instrIx != 0) {
                    val posBefore = UsePosition.toGapBefore(instrIx)
                    // XXX: Spill or reload first?
                    realizeSpillReload(isSpill = true, instrIx = posBefore, out = out)
                    realizeSpillReload(isSpill = false, instrIx = posBefore, out = out)
                }
                out += replaceRegInInstr(instrIx, instr)
            }
            it.replace(out)
        }
    }
}

private interface SpillReloadBuilder {
    fun addSpill(instrIx: Int, regPair: SpillReloadPair)
    fun addReload(instrIx: Int, regPair: SpillReloadPair)
}

data class SpillReloadPair(val virtual: Reg, val physical: Reg) {
    init {
        assert(virtual.isVirtual)
        assert(physical.isPhysical)
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

private fun LiveRange.splitBefore(instrIx: Int, sgen: SpillReloadBuilder): LiveRange {
    assert(firstUsePosition.instrIx < instrIx) {
        "${firstUsePosition.instrIx} < $instrIx failed"
    }
    assert(instrIx < lastUsePosition.instrIx)

    val inGroup = groupContains(instrIx)
    val splitOnListIx = nextListIxAfter(instrIx)
    val mine = poses.subList(0, splitOnListIx).toMutableList()
    val theirs = poses.subList(splitOnListIx, poses.size).toMutableList()
    if (inGroup) {
        // NOTE: Can't simply use (instrIx - 1) here, since instrIx can already be a gap position. In this
        // case, it's safe to just spill in the same place as spills happen before reloads.
        val spillAt = UsePosition.toGapBefore(instrIx)
        val reloadAt = UsePosition.toGapBefore(nextPositionAfter(instrIx)!!.instrIx)

        mine.add(UsePosition(virtualReg, spillAt, UsePosition.Kind.Read, OperandIx(0, isInput = true)))
        sgen.addSpill(spillAt, SpillReloadPair(virtualReg, physical = allocated!!))

        theirs.add(0, UsePosition(virtualReg, reloadAt,
                UsePosition.Kind.Write, OperandIx(0, isInput = false)))
        sgen.addReload(reloadAt, SpillReloadPair(virtualReg, physical = allocated!!))
    }
    poses = mine
    val res = LiveRange(theirs, parent = this)
    children += res
    return res
}

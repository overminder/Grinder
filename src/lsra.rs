#![allow(dead_code)]

use std::collections::HashMap;
use std::cmp::Ordering;

use ::x64::*;
use ::utils;

// Hints are attached here.
#[derive(Debug, Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash)]
struct LifetimePosition {
    ix: u32,
    local_offset: u8,
}


#[derive(Debug, Clone, Eq, PartialEq)]
struct UsePosition {
    pos: LifetimePosition,
    // | In v8 this is a pointer to the operand for this pos. In Rust however
    // we need to use indices rather than raw pointers.
    ctx: RegContext,
}

#[derive(Debug, Clone, Eq, PartialEq)]
struct UseInterval {
    start: LifetimePosition,
    end: LifetimePosition,
}

#[derive(Debug, Clone, Eq, PartialEq)]
struct LiveRange {
    intervals: Vec<UseInterval>,
    poses: Vec<UsePosition>,
    assigned: Option<MachReg>,
    split_to: Option<usize>,
    is_splinter: bool,
    spill_at: Option<LifetimePosition>,
    reload_at: Option<LifetimePosition>,
}

struct LinearScan {
    data: RegAllocData,
    unhandled_ranges: IxVec,
    active_ranges: IxVec,
    inactive_ranges: IxVec,
}

struct RegAllocData {
    block: Block,
    liveness: LiveRangeVec,
    num_regs_available: usize,
}

struct CommitRegAssignmentPhase {
    data: RegAllocData,
}

struct SpillSlotAllocator {
    next_slot: usize,
    spill_slots: HashMap<u32, usize>,
}

struct CommitSpillingPhase {
    alloc: SpillSlotAllocator,
    data: RegAllocData,
}

type IxVec = Vec<usize>;
type LiveRangeVec = Vec<LiveRange>;

// Impl

impl PartialOrd for UsePosition {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for UsePosition {
    fn cmp(&self, other: &Self) -> Ordering {
        let r = self.pos.cmp(&other.pos);
        if r == Ordering::Equal {
            self.ctx.reg.cmp(&other.ctx.reg)
        } else {
            r
        }
    }
}

impl LifetimePosition {
    fn new_gap_start(ix: usize) -> Self {
        Self::new(ix, GAP_START)
    }

    fn new_gap_end(ix: usize) -> Self {
        Self::new(ix, GAP_END)
    }

    fn new_instr_start(ix: usize) -> Self {
        Self::new(ix, INSTR_START)
    }

    fn new_instr_end(ix: usize) -> Self {
        Self::new(ix, INSTR_END)
    }

    fn from_computed(ix: usize) -> Self {
        Self::new(ix >> 2, (ix & 3) as u8)
    }

    fn new(ix: usize, local_offset: u8) -> Self {
        Self { ix: ix as u32, local_offset }
    }

    fn max() -> Self {
        Self::new_instr_end(usize::max_value())
    }

    fn gap_after(self) -> Self {
        debug_assert!(self.is_instr());
        Self::new_gap_start(self.ix as usize + 1)
    }

    fn gap_before(self) -> Self {
        debug_assert!(self.is_instr());
        Self::new_gap_end(self.ix as usize)
    }

    fn computed_ix(self) -> usize {
        (self.ix as usize) * STEP + (self.local_offset as usize)
    }

    fn instr_ix(self) -> usize {
        self.ix as usize
    }

    fn is_instr_start(self) -> bool {
        self.local_offset == INSTR_START
    }

    fn is_gap_start(self) -> bool {
        self.local_offset == GAP_START
    }

    fn is_gap_end(self) -> bool {
        self.local_offset == GAP_END
    }

    fn is_gap(self) -> bool {
        self.local_offset == GAP_START || self.local_offset == GAP_END
    }

    fn is_instr(self) -> bool {
        self.local_offset == INSTR_START || self.local_offset == INSTR_END
    }
}

impl UseInterval {
    fn new(start: LifetimePosition, end: LifetimePosition) -> Self {
        Self { start, end }
    }

    fn contains_pos(&self, pos: LifetimePosition) -> bool {
        self.start <= pos && pos < self.end
    }

    fn contains_pos_inclusive(&self, pos: LifetimePosition) -> bool {
        self.start <= pos && pos <= self.end
    }

    fn split_at(&mut self, before: LifetimePosition, after: LifetimePosition) -> Self {
        debug_assert!(self.contains_pos(before));
        debug_assert!(self.contains_pos_inclusive(after));
        let r = Self::new(after, self.end);
        self.end = before;
        r
    }

    fn first_intersection(&self, it: &UseInterval) -> Option<LifetimePosition> {
        if it.start < self.start {
            it.first_intersection(self)
        } else if it.start < self.end {
            Some(it.start)
        } else {
            None
        }
    }
}

impl UsePosition {
    fn new(pos: LifetimePosition, ctx: RegContext) -> Self {
        Self { pos, ctx }
    }

    fn reg(&self) -> Reg {
        self.ctx.reg
    }

    fn is_input(&self) -> bool {
        self.ctx.kind == UseKind::Input
    }

    fn is_output(&self) -> bool {
        !self.is_input()
    }
}


const STEP: usize = 4;
const HALF_STEP: usize = 2;

const GAP_START: u8 = 0;
const GAP_END: u8 = 1;
const INSTR_START: u8 = 2;
const INSTR_END: u8 = 3;

impl LiveRange {
    fn new() -> Self {
        Self {
            intervals: vec![],
            poses: vec![],
            assigned: None,
            split_to: None,
            is_splinter: false,
            spill_at: None,
            reload_at: None,
        }
    }

    fn has_reg_assigned(&self) -> bool {
        self.assigned.is_some()
    }

    fn set_assigned_reg(&mut self, r: MachReg) {
        debug_assert!(!self.has_reg_assigned());
        self.assigned = Some(r);
    }

    fn assigned_reg(&self) -> MachReg {
        self.assigned.unwrap()
    }

    fn contains_pos(&self, pos: LifetimePosition) -> bool {
        self.intervals.iter().find(|it| it.contains_pos(pos)).is_some()
    }

    fn add_interval(&mut self, interval: UseInterval) {
        // Remember to sort this.
        self.intervals.push(interval);
    }

    fn add_pos(&mut self, pos: UsePosition) {
        self.poses.push(pos);
    }

    fn split_poses_at(&mut self, splinter_start: LifetimePosition) -> Vec<UsePosition> {
        let next_ix = self.poses.iter()
            .position(|u| {
                if u.is_input() {
                    // pos is an output position so it can never have the same position
                    // as another input position.
                    debug_assert!(u.pos != splinter_start);
                }
                u.pos >= splinter_start
            })
            .unwrap();
        debug_assert!(self.poses[next_ix - 1].pos < splinter_start);
        self.poses.split_off(next_ix)
    }

    // before must be included in self.intervals after the split,
    // after must be included in splinters after the split.
    fn split_intervals(&mut self,
                       before: LifetimePosition, after: LifetimePosition,
                       needs_spill_reload: bool) -> Vec<UseInterval> {
        let before_ix = self.intervals.iter()
            .position(|it| it.contains_pos_inclusive(before))
            .unwrap();
        let next_ix = before_ix + 1;
        if needs_spill_reload {
            // `after` must be an input. `before` and `after` must be in the same interval.
            debug_assert!(self.intervals[before_ix].end != before);
            debug_assert!(self.intervals[before_ix].contains_pos_inclusive(after));
            let splinter = self.intervals[before_ix].split_at(before, after);
            let mut splinters = self.intervals.split_off(next_ix);
            splinters.insert(0, splinter);
            splinters
        } else {
            // `after` must be an output. `before` and `after` must be in two
            // consecutive intervals.
            debug_assert!(self.intervals[before_ix].end == before);
            debug_assert!(self.intervals[next_ix].start == after);
            self.intervals.split_off(next_ix)
        }
    }

    fn split_at(&mut self, splinter_start: LifetimePosition, fresh_range_ix: usize) -> Self {
        self.debug_check_interior_sorted();
        // | Necessarily true?
        // debug_assert!(pos.is_instr_start());
        // Find the pos before and after ix.

        let splinter_poses = self.split_poses_at(splinter_start);
        // Eager spill, right after the last use before pos.
        let before = self.last_pos().pos;
        // Lazy reload, just before the first use after pos.
        let after = splinter_poses[0].pos;

        // For * - split - def, we don't need to do spill and reload.
        let needs_spill_reload = !splinter_poses[0].is_output();
        let splinter_intervals = self.split_intervals(before, after,
                                                      needs_spill_reload);
        self.split_to = Some(fresh_range_ix);
        self.spill_at = utils::some_if(needs_spill_reload, || before.gap_after());
        let res = Self {
            intervals: splinter_intervals,
            poses: splinter_poses,
            assigned: None,
            is_splinter: true,
            split_to: None,
            spill_at: None,
            reload_at: utils::some_if(needs_spill_reload, || after.gap_before()),
        };
        println!("split({:#?}) -> {:#?} + {:#?}", splinter_start, self, res);
        res
    }

    fn debug_check_interior_sorted(&self) {
        debug_assert!(self.check_interior_sorted().is_ok());
    }

    fn check_interior_sorted(&self) -> Result<(), String> {
        utils::ensure_sorted(&mut self.intervals.iter().map(|x| x.start))?;
        utils::ensure_sorted(&mut self.poses.iter())?;
        Ok(())
    }

    fn sort_interior_by_start(&mut self) {
        self.intervals.sort_by_key(|it| it.start);
        self.poses.sort();
    }

    fn cmp_by_first_start(&self, other: &LiveRange) -> Ordering {
        let r = self.first_interval().start.cmp(&other.first_interval().start);
        if r == Ordering::Equal {
            self.reg().cmp(&other.reg())
        } else {
            r
        }
    }

    fn first_interval(&self) -> &UseInterval {
        &self.intervals[0]
    }

    fn first_pos(&self) -> &UsePosition {
        &self.poses[0]
    }

    fn first_use_after(&self, before: LifetimePosition) -> Option<LifetimePosition> {
        self.debug_check_interior_sorted();
        self.poses.iter()
            .map(|p| p.pos)
            .find(|p| &before < p)
    }

    fn last_interval(&self) -> &UseInterval {
        self.intervals.last().unwrap()
    }

    fn last_pos(&self) -> &UsePosition {
        self.poses.last().unwrap()
    }

    fn first_intersection(&self, pos: &LiveRange) -> Option<LifetimePosition> {
        self.intervals.iter()
            .flat_map(|it| pos.intervals.iter()
                      .flat_map(|it2| it.first_intersection(it2))
                      .next())
            .next()
    }

    fn is_for(&self, r: Reg) -> bool {
        self.reg() == r
    }

    fn reg(&self) -> Reg {
        self.first_pos().reg()
    }
}


fn find_or_create_live_range(ranges: &mut LiveRangeVec, r: Reg) -> &mut LiveRange {
    let ix;
    if let Some(ix_) = ranges.iter().position(|rg| rg.is_for(r)) {
        ix = ix_;
    } else {
        ix = ranges.len();
        ranges.push(LiveRange::new());
    }
    &mut ranges[ix]
}

fn add_interval_and_poses_to_live_range(ranges: &mut LiveRangeVec, reg: Reg,
                                        interval: UseInterval, poses: Vec<UsePosition>) {
    // Maybe also add hint.
    let range = find_or_create_live_range(ranges, reg);
    range.add_interval(interval);
    for pos in poses {
        range.add_pos(pos);
    }
}

fn analyze_block_liveness(b: &Block) -> LiveRangeVec {
    let mut live: HashMap<Reg, Vec<UsePosition>> = HashMap::new();
    let mut ranges = vec![];
    // FIXME: use actual value.
    let block_id = 0;

    // XXX: Hack to make sure that %rax is live at the end of the block.
    // let last_ix = LifetimePosition::new_instr_end(b.instrs().len() - 1);
    // live.insert(Reg::new_mach(0), vec![last_ix]);

    for (instr_ix, instr) in b.instrs().iter().enumerate().rev() {
        // An instruction defines its dst at the end of the ix.
        let pos_end = LifetimePosition::new_instr_end(instr_ix);
        for (operand_ix, output) in instr.outputs() {
            let octx = RegContext::new_output(output, block_id, instr_ix, operand_ix);
            if let Some(pos_uses) = live.remove(&output) {
                let interval = UseInterval::new(
                    pos_end,
                    pos_uses.iter().last().unwrap().pos);
                let mut poses = vec![UsePosition::new(pos_end, octx)];
                poses.extend(pos_uses.into_iter());
                add_interval_and_poses_to_live_range(&mut ranges, output, interval, poses);
            } else {
                // A def that is not used.
                println!("[WARN] Unused output: {:?} @ {}", output, instr_ix);
            }
        }
        // An instruction uses its srcs at the start of the ix.
        for (operand_ix, input) in instr.inputs() {
            let ictx = RegContext::new_input(input, block_id, instr_ix, operand_ix);
            let mut pos_start = vec![
                UsePosition::new(LifetimePosition::new_instr_start(instr_ix), ictx)
            ];
            if let Some(pos_next_uses) = live.remove(&input) {
                // Multiple uses before a def: add them all.
                pos_start.extend(pos_next_uses);
            }
            live.insert(input, pos_start);
        }
    }

    for ref mut range in &mut ranges {
        range.sort_interior_by_start();
    }

    // Just for better testability.
    ranges.sort_by(|x, y| x.cmp_by_first_start(y));

    ranges
}

impl RegAllocData {
    fn new(block: Block, liveness: LiveRangeVec, num_regs_available: usize) -> Self {
        Self {
            block,
            liveness,
            num_regs_available,
        }
    }
}

impl LinearScan {
    fn new(data: RegAllocData) -> Self {
        Self {
            unhandled_ranges: (0..data.liveness.len()).collect(),
            active_ranges: vec![],
            inactive_ranges: vec![],
            data,
        }
    }

    fn add_unhandled_and_sort(&mut self, range: LiveRange) {
        let ix = self.data.liveness.len();
        self.data.liveness.push(range);
        // Could also do a binary insertion which costs O(N) rather than O(NlogN).
        self.unhandled_ranges.push(ix);
        self.sort_unhandled();
    }

    fn sort_unhandled(&mut self) {
        sort_unhandled(&self.data.liveness, &mut self.unhandled_ranges);
    }

    fn run(&mut self) {
        self.sort_unhandled();

        while let Some(current_range_ix) = self.unhandled_ranges.pop() {
            self.prepare_current_ix(current_range_ix);
            self.process_current_ix(current_range_ix);
        }
    }

    fn prepare_current_ix(&mut self, current_ix: usize) {
        let ref current = self.data.liveness[current_ix];
        let start = current.first_interval().start;

        shuffle_active_inactive(start, true,
                                &mut self.active_ranges,
                                &mut self.inactive_ranges,
                                &self.data.liveness);

        shuffle_active_inactive(start, false,
                                &mut self.inactive_ranges,
                                &mut self.active_ranges,
                                &self.data.liveness);

        debug_assert!(!current.has_reg_assigned());
    }

    fn process_current_ix(&mut self, current_ix: usize) {
        // XXX: Missing fixed reg handling.
        if let Some((mreg, pos)) = self.farthest_free_until_reg(current_ix) {
            if !self.try_allocate_free_reg(current_ix, mreg, pos) {
                // Must be partially allocatable.
                // XXX: also call pos.gap_before()?
                self.allocate_partially_free_reg(current_ix, mreg, pos);
                println!("alloc_partially_free_reg({:?}, {:#?})", mreg, self.data.liveness[current_ix]);
            } else {
                println!("alloc_free_reg({:?}, {:#?})", mreg, self.data.liveness[current_ix]);
            }
        } else {
            // All blocked. Spill from an active range.
            self.allocate_blocked_reg(current_ix);
            println!("alloc_blocked_reg({:#?})", self.data.liveness[current_ix]);
        }
    }

    fn try_allocate_free_reg(&mut self,
                             current_ix: usize, mreg: MachReg,
                             free_until: LifetimePosition) -> bool {
        let ref mut current = self.data.liveness[current_ix];
        if current.last_interval().end <= free_until {
            // Allocatable for the whole range.
            current.set_assigned_reg(mreg);
            self.active_ranges.push(current_ix);
            true
        } else {
            let partially = current.first_interval().start < free_until;
            debug_assert!(partially,
                          "free_until_reg {:?} @ pos {:?} <= current {:?}.",
                          mreg, free_until, current);
            false
        }
    }

    fn allocate_partially_free_reg(&mut self,
                                      current_ix: usize, mreg: MachReg,
                                      free_until: LifetimePosition) {
        self.split_and_assign_reg(current_ix, free_until, mreg, None);
        self.active_ranges.push(current_ix);
    }

    fn split_and_assign_reg(&mut self,
                            range_ix_to_split: usize,
                            splinter_start: LifetimePosition,
                            reg: MachReg,
                            assign_to: Option<usize>) {
        let splinter = {
            let splinter_ix = self.data.liveness.len();
            let assign_to = assign_to.unwrap_or(splinter_ix);
            let r = self.data.liveness[range_ix_to_split]
                .split_at(splinter_start, splinter_ix);
            self.data.liveness[assign_to].set_assigned_reg(reg);
            r
        };
        self.add_unhandled_and_sort(splinter);
    }


    fn allocate_blocked_reg(&mut self, current_ix: usize) {
        let (mreg, (range_ix, pos)) = self.farthest_next_reg_use(current_ix);
        let (first_pos, last_pos) = {
            let ref current = self.data.liveness[current_ix];
            (current.first_pos().pos, current.last_pos().pos)
        };
        if pos < first_pos {
            panic!("No impl: need to spill pos.");
        } else if pos < last_pos {
            // Partially free, split the other range.
            self.split_and_assign_reg(range_ix, first_pos,
                                      mreg, Some(current_ix));
            self.active_ranges.push(current_ix);
        } else if pos == first_pos {
            panic!("Too many register uses in one position: {:?}", pos);
        } else {
            // Reg is entirely free.
            self.data.liveness[current_ix].set_assigned_reg(mreg);
            self.active_ranges.push(current_ix);
        }
    }

    fn farthest_free_until_reg(&self,
                               current_ix: usize) -> Option<(MachReg, LifetimePosition)> {
        // NOTE: None is smaller than any Some(_).
        self.find_free_until_regs(current_ix)
            .iter()
            // | Add reg_ix
            .enumerate()
            // | Find farthest range, preferring smaller reg_ix
            .max_by_key(|&(reg_ix, p)| (p, -(reg_ix as isize)))
            .iter()
            // | Join nested options
            .flat_map(|&(ix, mb_p)| mb_p.map(|p| (MachReg::new(ix), p)))
            .next()
    }

    fn farthest_next_reg_use(&self,
                             current_ix: usize) -> (MachReg, (usize, LifetimePosition)) {
        self.find_next_reg_uses(current_ix)
            .into_iter().enumerate()
            // | Find farthest range, preferring smaller reg_ix
            .max_by_key(|&(reg_ix, (_, p))| (p, -(reg_ix as isize)))
            .map(|(reg_ix, p)| (MachReg::new(reg_ix), p))
            .unwrap()
    }

    fn find_free_until_regs(&self, current_ix: usize) -> Vec<Option<LifetimePosition>> {
        let ref current = self.data.liveness[current_ix];
        let mut free_until = vec![Some(LifetimePosition::max()); self.data.num_regs_available];

        for (_, active_range) in self.active_ranges() {
            // All the regs occupied by active ranges are not available.
            free_until[active_range.assigned_reg().ix()] = None;
        }

        for (_, inactive_range) in self.inactive_ranges() {
            // Some of the inactive ranges might leave lifetime holes.
            if let Some(sect) = inactive_range.first_intersection(current) {
                let reg_ix = inactive_range.assigned_reg().ix();
                utils::inplace_min(&mut free_until[reg_ix], Some(sect));
            }
        }

        free_until
    }

    fn find_next_reg_uses(&self, current_ix: usize) -> Vec<(usize, LifetimePosition)> {
        let mut next_use = vec![(usize::max_value(), LifetimePosition::max());
                                self.data.num_regs_available];
        let ref current = self.data.liveness[current_ix];

        for (range_ix, active_range) in self.active_ranges() {
            // TODO: Respect fixed / non-spillable ranges.
            let reg_ix = active_range.assigned_reg().ix();
            // V8 might spill an active range earlier if its next reg use is not beneficial.
            if let Some(u) = active_range.first_use_after(current.first_pos().pos) {
                // FIXME: There might not exist a use after current in active_range,
                // if the later is a split parent... Or not?
                utils::inplace_min_by(&mut next_use[reg_ix], (range_ix, u), |x, y| x.1 < y.1);
            }
        }

        for (range_ix, inactive_range) in self.inactive_ranges() {
            // TODO: Respect fixed ranges.
            let reg_ix = inactive_range.assigned_reg().ix();
            if let Some(sect) = inactive_range.first_intersection(current) {
                utils::inplace_min_by(&mut next_use[reg_ix], (range_ix, sect), |x, y| x.1 < y.1);
            }
        }

        next_use
    }

    fn active_ranges<'a>(&'a self) -> impl Iterator<Item=(usize, &LiveRange)> + 'a {
        self.active_ranges.iter().cloned().map(move |ix| (ix, &self.data.liveness[ix]))
    }

    fn inactive_ranges<'a>(&'a self) -> impl Iterator<Item=(usize, &LiveRange)> + 'a {
        self.inactive_ranges.iter().cloned().map(move |ix| (ix, &self.data.liveness[ix]))
    }
}


fn sort_unhandled(ranges: &LiveRangeVec, unhandled: &mut IxVec) {
    unhandled.sort_by(|x, y| ranges[*x].cmp_by_first_start(&ranges[*y]).reverse())
}

fn shuffle_active_inactive(start: LifetimePosition, from_should_contain: bool,
                           from: &mut IxVec, to: &mut IxVec, ranges: &LiveRangeVec) {
    let mut i = 0;
    while i < from.len() {
        let range_ix = from[i];
        let ref thiz = ranges[range_ix];
        if thiz.last_interval().end < start {
            // Finished handling this range: <from> -> handled
            from.swap_remove(i);
        } else if thiz.contains_pos(start) != from_should_contain {
            // <from> -> <to>
            to.push(from.swap_remove(i));
        } else {
            // Keep this.
            i += 1;
        }
    }
}

impl CommitRegAssignmentPhase {
    fn new(data: RegAllocData) -> Self {
        Self { data }
    }

    fn run(&mut self) {
        for range in &self.data.liveness {
            let reg = range.reg();
            if reg.is_mach() {
                continue;
            }
            let mreg = range.assigned.unwrap();
            for pos in &range.poses {
                let ref ctx = pos.ctx;
                let instr = &mut self.data.block.instrs[ctx.instr_ix as usize];
                instr.set_reg_at(&ctx.operand_ix, Reg::Mach(mreg));
            }
        }
    }
}

impl SpillSlotAllocator {
    fn new() -> Self {
        Self {
            spill_slots: HashMap::new(),
            next_slot: 1,
        }
    }

    fn slot_for(&mut self, reg: &Reg) -> Operand {
        let next_slot = &mut self.next_slot;
        let ix = self.spill_slots.entry(reg.virt_ix()).or_insert_with(|| {
            let ix = *next_slot;
            *next_slot += 1;
            ix
        });
        Mem {
            base: Reg::rsp(),
            index: None,
            disp: (*ix << 3) as u32,
        }.into_op()
    }
}

impl CommitSpillingPhase {
    fn new(data: RegAllocData) -> Self {
        Self {
            alloc: SpillSlotAllocator::new(),
            data,
        }
    }


    fn run(&mut self) {
        let instrs = &mut self.data.block.instrs;
        for range in &self.data.liveness {
            let vr = range.reg();
            let mr = range.assigned_reg().into_reg();
            if let Some(pos) = range.reload_at {
                debug_assert!(pos.is_gap_end());
                let slot = self.alloc.slot_for(&vr);
                let reload = ParallelMove::new(mr.into_op(), slot);
                instrs[pos.instr_ix()]
                    .parallel_moves
                    .add_to_start(reload);
            }
            if let Some(pos) = range.spill_at {
                debug_assert!(pos.is_gap_start());
                let slot = self.alloc.slot_for(&vr);
                let spill = ParallelMove::new(slot, mr.into_op());
                instrs[pos.instr_ix() - 1]
                    .parallel_moves
                    .add_to_end(spill);
            }
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use ::test_utils;

    fn vreg(ix: u32) -> Reg {
        Reg::new_virt(ix)
    }

    fn mreg(ix: u32) -> Reg {
        Reg::new_mach(ix)
    }

    fn op_vreg(ix: u32) -> Operand {
        Operand::new_virt_reg(ix)
    }

    fn op_mreg(ix: u32) -> Operand {
        Operand::new_mach_reg(ix)
    }

    fn pos_start(ix: usize) -> usize {
        LifetimePosition::new_instr_start(ix).computed_ix()
    }

    fn pos_end(ix: usize) -> usize {
        LifetimePosition::new_instr_end(ix).computed_ix()
    }

    fn dst_reg() -> RegLocInInstr {
        RegLocInInstr::Dst(RegLocInOp::Reg)
    }

    fn src_reg() -> RegLocInInstr {
        RegLocInInstr::Src(RegLocInOp::Reg)
    }

    fn live_range(r: Reg, intervals: &[usize],
                  poses: &[(usize, RegLocInInstr, UseKind)]) -> LiveRange {
        let mut rg = LiveRange::new();
        for i in 0..(intervals.len() / 2) {
            let start = intervals[i * 2];
            let end = intervals[i * 2 + 1];
            rg.add_interval(UseInterval::new(
                LifetimePosition::from_computed(start),
                LifetimePosition::from_computed(end)));
        }
        for &(pos, ref operand_ix, kind) in poses {
            let pos = LifetimePosition::from_computed(pos);
            let instr_ix = pos.instr_ix();
            rg.add_pos(UsePosition::new(
                pos,
                RegContext::new(r, kind, 0, instr_ix, operand_ix.clone())));
        }
        rg
    }

    fn simple_block_nospill() -> Block {
        let v0 = op_vreg(0);
        let v1 = op_vreg(1);
        let m0 = op_mreg(0);
        let instrs = vec![
            Instr::mov(v0.clone(), Operand::Imm(42)),
            Instr::mov(v1.clone(), Operand::Imm(0)),
            Instr::add(v1.clone(), v0.clone()),
            Instr::add(v1.clone(), v0.clone()),
            Instr::mov(m0.clone(), v1.clone()),
            Instr::ret(m0.clone()),
        ];
        Block::new(instrs)
    }

    fn simple_block_spill() -> Block {
        let v0 = op_vreg(0);
        let v1 = op_vreg(1);
        let v2 = op_vreg(2);
        let v3 = op_vreg(3);
        let v4 = op_vreg(4);
        let m0 = op_mreg(0);
        let instrs = vec![
            Instr::mov(v0.clone(), Operand::Imm(0)),
            Instr::mov(v1.clone(), Operand::Imm(1)),
            Instr::mov(v2.clone(), Operand::Imm(2)),
            Instr::mov(v3.clone(), Operand::Imm(3)),
            // Instr::mov(v4.clone(), Operand::Imm(4)),
            Instr::add(v0.clone(), v1.clone()),
            Instr::add(v0.clone(), v2.clone()),
            Instr::add(v0.clone(), v3.clone()),
            // Instr::add(v0.clone(), v4.clone()),
            // Instr::mov(m0.clone(), v0.clone()),
            Instr::ret(v0.clone()),
        ];
        Block::new(instrs)
    }

    #[test]
    fn can_analyze_live_ranges_for_single_block() {
        let b = simple_block_nospill();
        let ls = analyze_block_liveness(&b);
        let rg0 = live_range(vreg(0), &[
            pos_end(0), pos_start(3),
        ], &[
            (pos_end(0), dst_reg(), UseKind::Output),
            (pos_start(2), src_reg(), UseKind::Input),
            (pos_start(3), src_reg(), UseKind::Input),
        ]);
        let rg1 = live_range(vreg(1), &[
            pos_end(1), pos_start(2),
            pos_end(2), pos_start(3),
            pos_end(3), pos_start(4),
        ], &[
            (pos_end(1), dst_reg(), UseKind::Output),
            (pos_start(2), dst_reg(), UseKind::Input),
            (pos_end(2), dst_reg(), UseKind::Output),
            (pos_start(3), dst_reg(), UseKind::Input),
            (pos_end(3), dst_reg(), UseKind::Output),
            (pos_start(4), src_reg(), UseKind::Input),
        ]);
        let rg2 = live_range(mreg(0), &[
            pos_end(4), pos_start(5)
        ], &[
            (pos_end(4), dst_reg(), UseKind::Output),
            (pos_start(5), src_reg(), UseKind::Input),
        ]);
        let expected = vec![rg0, rg1, rg2];
        test_utils::assert_eq_pretty("analyze-liveness-1block", &ls, &expected);
    }

    #[test]
    fn can_lsra_for_single_block_nospill() {
        let block = simple_block_nospill();
        let liveness = analyze_block_liveness(&block);
        let mut lsra = LinearScan::new(RegAllocData::new(block, liveness, 4));
        lsra.run();
        let mut assignment = CommitRegAssignmentPhase::new(lsra.data);
        assignment.run();

        let m0 = op_mreg(0);
        let m1 = op_mreg(1);
        let expected_instrs = vec![
            Instr::mov(m0.clone(), Operand::Imm(42)),
            Instr::mov(m1.clone(), Operand::Imm(0)),
            Instr::add(m1.clone(), m0.clone()),
            Instr::add(m1.clone(), m0.clone()),
            Instr::mov(m0.clone(), m1.clone()),
            Instr::ret(m0.clone()),
        ];

        test_utils::assert_eq_pretty("lsra-instr-1block-nospill",
                                     &assignment.data.block.instrs, &expected_instrs);
    }

    #[test]
    fn can_lsra_for_single_block_spill() {
        let block = simple_block_spill();
        let liveness = analyze_block_liveness(&block);
        let mut lsra = LinearScan::new(RegAllocData::new(block, liveness, 2));
        lsra.run();
        let mut rass = CommitRegAssignmentPhase::new(lsra.data);
        rass.run();
        let mut spill = CommitSpillingPhase::new(rass.data);
        spill.run();

        let m0 = op_mreg(0);
        let m1 = op_mreg(1);
        let expected_instrs = vec![
            Instr::mov(m0.clone(), Operand::Imm(42)),
            Instr::mov(m1.clone(), Operand::Imm(0)),
            Instr::add(m1.clone(), m0.clone()),
            Instr::add(m1.clone(), m0.clone()),
            Instr::mov(m0.clone(), m1.clone()),
            Instr::ret(m0.clone()),
        ];

        test_utils::assert_eq_pretty("lsra-instr-1block-spill",
                                     &spill.data.block.instrs, &expected_instrs);
    }
}


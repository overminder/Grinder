#![allow(dead_code)]

use std::collections::HashMap;
use std::cmp::Ordering;
use std::{cmp, mem, fmt};

use ::x64::*;

// Hints are attached here.
#[derive(Debug, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
struct LifetimePosition {
    ix: u32,
    local_offset: u8,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Ord, PartialOrd)]
enum UseKind {
    Input,
    Output,
}

#[derive(Debug, Clone, Eq, PartialEq)]
struct UseDescr {
    reg: Reg,
    kind: UseKind,
}

#[derive(Debug, Clone, Eq, PartialEq)]
struct UsePosition {
    pos: LifetimePosition,
    // | Actually this should be a pointer to the operand for this pos.
    op: UseDescr,
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
            self.op.reg.cmp(&other.op.reg)
        } else {
            r
        }
    }
}

impl LifetimePosition {
    fn new_gap_start(ix: usize) -> Self {
        LifetimePosition::new(ix, GAP_START)
    }

    fn new_gap_end(ix: usize) -> Self {
        LifetimePosition::new(ix, GAP_END)
    }

    fn new_instr_start(ix: usize) -> Self {
        LifetimePosition::new(ix, INSTR_START)
    }

    fn new_instr_end(ix: usize) -> Self {
        LifetimePosition::new(ix, INSTR_END)
    }

    fn from_raw(ix: usize) -> Self {
        LifetimePosition::new(ix >> 2, (ix & 3) as u8)
    }

    fn new(ix: usize, local_offset: u8) -> Self {
        LifetimePosition { ix: ix as u32, local_offset }
    }

    fn max() -> Self {
        LifetimePosition::new_instr_end(usize::max_value())
    }

    fn ix(self) -> usize {
        (self.ix as usize) * STEP + (self.local_offset as usize)
    }

    fn is_instr_start(self) -> bool {
        self.local_offset == INSTR_START
    }
}

impl UseInterval {
    fn new(start: LifetimePosition, end: LifetimePosition) -> Self {
        UseInterval { start, end }
    }

    fn contains_pos(&self, pos: LifetimePosition) -> bool {
        self.start <= pos && pos < self.end
    }

    fn first_intersection(&self, it: &UseInterval) -> Option<LifetimePosition> {
        let mut lhs = self;
        let mut rhs = it;
        if lhs.start > rhs.start {
            // Make sure lhs starts before rhs's start
            mem::swap(&mut lhs, &mut rhs);
        }
        if lhs.end <= rhs.start {
            // rhs starts after lhs's end: no intersection.
            None
        } else {
            Some(rhs.start)
        }
    }
}

impl UsePosition {
    fn new(pos: LifetimePosition, op: UseDescr) -> Self {
        UsePosition { pos, op }
    }

    fn new_input(pos: LifetimePosition, reg: Reg) -> Self {
        UsePosition::new(pos, UseDescr { reg, kind: UseKind::Input })
    }

    fn new_output(pos: LifetimePosition, reg: Reg) -> Self {
        UsePosition::new(pos, UseDescr { reg, kind: UseKind::Output })
    }

    fn is_input(&self) -> bool {
        self.op.kind == UseKind::Input
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
        LiveRange {
            intervals: vec![],
            poses: vec![],
            assigned: None,
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

    fn split_poses_at(&mut self, pos: LifetimePosition) -> Vec<UsePosition> {
        let next_ix = {
            let mut prev = None;
            let mut next = None;
            for (u_ix, u) in self.poses.iter().enumerate() {
                if u.is_input() {
                    // ix is an output position so it can never have the same position
                    // as another input position.
                    debug_assert!(u.pos != pos);
                }
                if u.pos <= pos {
                    prev = Some(u);
                } else {
                    // Found first pos after pos.
                    if let Some(prev) = prev {
                        // So the previous pos must be before or equals to ix.
                        debug_assert!(prev.pos <= pos);
                    }
                    next = Some((u_ix, u));
                    break;
                }
            }

            prev.unwrap();
            let (next_ix, _) = next.unwrap().clone();
            next_ix
        };

        self.poses.split_off(next_ix)
    }

    fn split_at(&mut self, pos: LifetimePosition) -> Self {
        debug_assert!(self.check_interior_sorted().is_ok());
        // | Necessarily true?
        debug_assert!(pos.is_instr_start());
        // Find the pos before and after ix.

        let splinter_poses = self.split_poses_at(pos);
        let next_pos = splinter_poses[0].pos;
        let prev_pos = self.last_pos().pos;

        // For * - split - def, we don't need to do spill and reload.
        let needs_spill_reload = !splinter_poses[0].is_output();

        if needs_spill_reload {
            panic!("No impl");
        } else {
            let next_ix = {
                let mut prev_it = None;
                let mut next_it = None;
                for (it_ix, it) in self.intervals.iter().enumerate() {
                    if it.end == prev_pos {
                        prev_it = Some(it);
                    } else if it.start == next_pos {
                        debug_assert!(prev_it.is_some());
                        next_it = Some((it_ix, it));
                        break;
                    }
                }
                prev_it.unwrap();
                let (next_ix, _) = next_it.unwrap();
                next_ix
            };
            let splinter_intervals = self.intervals.split_off(next_ix);
            LiveRange {
                intervals: splinter_intervals,
                poses: splinter_poses,
                assigned: None,
            }
        }
    }

    fn check_interior_sorted(&self) -> Result<(), String> {
        ensure_sorted(&mut self.intervals.iter().map(|x| x.start))?;
        ensure_sorted(&mut self.poses.iter())?;
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

    fn first_interval_start_ix(&self) -> usize {
        self.first_interval().start.ix()
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
        self.first_pos().op.reg
    }
}

fn ensure_sorted<A: Ord + fmt::Debug>(it: &mut Iterator<Item=A>) -> Result<(), String> {
    let mut last = None;
    for a in it {
        if let Some(last) = last {
            if !(last <= a) {
                return Err(format!("Failed to assert {:?} <= {:?}", last, a));
            }
        }
        last = Some(a);
    }
    Ok(())
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
    let mut live: HashMap<Reg, Vec<LifetimePosition>> = HashMap::new();
    let mut ranges = vec![];

    // XXX: Hack to make sure that %rax is live at the end of the block.
    // let last_ix = LifetimePosition::new_instr_end(b.instrs().len() - 1);
    // live.insert(Reg::new_mach(0), vec![last_ix]);

    for (ix, instr) in b.instrs().iter().enumerate().rev() {
        // An instruction defines its dst at the end of the ix.
        let pos_end = LifetimePosition::new_instr_end(ix);
        for output in instr.outputs() {
            if let Some(pos_uses) = live.remove(&output) {
                let interval = UseInterval::new(pos_end, *pos_uses.iter().last().unwrap());
                let mut poses = vec![UsePosition::new_output(pos_end, output)];
                for u in pos_uses {
                    poses.push(UsePosition::new_input(u, output));
                }
                add_interval_and_poses_to_live_range(&mut ranges, output, interval, poses);
            } else {
                // A def that is not used.
                println!("[WARN] Unused output: {:?} @ {}", output, ix);
            }
        }
        // An instruction uses its srcs at the start of the ix.
        for input in instr.inputs() {
            let mut pos_start = vec![LifetimePosition::new_instr_start(ix)];
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

impl LinearScan {
    fn new(data: RegAllocData) -> Self {
        LinearScan {
            unhandled_ranges: (0..data.liveness.len()).collect(),
            active_ranges: vec![],
            inactive_ranges: vec![],
            data,
        }
    }

    fn run(&mut self) {
        sort_unhandled(&self.data.liveness, &mut self.unhandled_ranges);

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
        if let Some((mreg, largest_fit)) = self.largest_free_until_reg(current_ix) {
            {
                let ref mut current = self.data.liveness[current_ix];
                if largest_fit >= current.last_interval().end {
                    // Allocatable for the whole range.
                    current.set_assigned_reg(mreg);
                    self.active_ranges.push(current_ix);
                    return;
                }
                let partially = largest_fit > current.first_interval().start;
                debug_assert!(partially,
                              "free_until_reg {:?} @ pos {:?} is smaller than current {:?}.",
                              mreg, largest_fit, current);
            }

            // Partially allocatable.
            let splintee = self.data.liveness[current_ix].split_at(largest_fit);
            self.data.liveness.push(splintee);

        } else {
            // All blocked. Spill from an active range.
        }
    }

    fn largest_free_until_reg(&self,
                              current_ix: usize) -> Option<(MachReg, LifetimePosition)> {
        // NOTE: None is smaller than any Some(_).
        self.find_free_until_regs(current_ix)
            .iter()
            // | Add reg_ix
            .enumerate()
            // | Find largest
            .max_by_key(|&(_, p)| p)
            .iter()
            // | Join nested options
            .flat_map(|&(ix, mb_p)| mb_p.map(|p| (MachReg::new(ix), p)))
            .next()
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
                let assigned_ix = inactive_range.assigned_reg().ix();
                free_until[assigned_ix] = cmp::min(free_until[assigned_ix], Some(sect));
            }
        }

        free_until
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


#[cfg(test)]
mod test {
    use super::*;

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
        LifetimePosition::new_instr_start(ix).ix()
    }

    fn pos_end(ix: usize) -> usize {
        LifetimePosition::new_instr_end(ix).ix()
    }

    fn live_range(r: Reg, intervals: &[usize], poses: &[(usize, UseKind)]) -> LiveRange {
        let mut rg = LiveRange::new();
        for i in 0..(intervals.len() / 2) {
            let start = intervals[i * 2];
            let end = intervals[i * 2 + 1];
            rg.add_interval(UseInterval::new(
                LifetimePosition::from_raw(start),
                LifetimePosition::from_raw(end)));
        }
        for &(ix, kind) in poses {
            rg.add_pos(UsePosition::new(
                LifetimePosition::from_raw(ix),
                UseDescr { reg: r, kind }));
        }
        rg
    }

    #[test]
    fn simple_live_ranges() {
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
        let b = Block::new(instrs);
        let ls = analyze_block_liveness(&b);
        let rg0 = live_range(vreg(0), &[
            pos_end(0), pos_start(3),
        ], &[
            (pos_end(0), UseKind::Output),
            (pos_start(2), UseKind::Input),
            (pos_start(3), UseKind::Input),
        ]);
        let rg1 = live_range(vreg(1), &[
            pos_end(1), pos_start(2),
            pos_end(2), pos_start(3),
            pos_end(3), pos_start(4),
        ], &[
            (pos_end(1), UseKind::Output),
            (pos_start(2), UseKind::Input),
            (pos_end(2), UseKind::Output),
            (pos_start(3), UseKind::Input),
            (pos_end(3), UseKind::Output),
            (pos_start(4), UseKind::Input),
        ]);
        let rg2 = live_range(mreg(0), &[
            pos_end(4), pos_start(5)
        ], &[
            (pos_end(4), UseKind::Output),
            (pos_start(5), UseKind::Input),
        ]);
        let expected = vec![rg0, rg1, rg2];
        assert!(ls == expected, "{:#?} == {:#?}", ls, expected);
    }
}


use std::{fmt, iter};

// Assembly instructions, in a machine-independent way.
// This is used by regallocs.

#[derive(Debug)]
pub enum MachOperand {
    Read(Reg),
    // Constant(usize),
    Write(Reg),
}

#[derive(Debug, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum Reg {
    Virtual(VirtualReg),
    Fixed(MachReg),
}

#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct VirtualReg(u32);
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct MachReg(u32);

#[derive(Debug)]
pub struct Location {
    regs: Vec<Reg>,
}

#[derive(Debug, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum MachOpCode {
    Mov,
    Add,
}

#[derive(Debug)]
pub struct MachInstr {
    kind: MachOpCode,
    ops: Vec<MachOperand>,
}

// LSRA

#[derive(Debug)]
struct UsePosition {
    ix: usize,
}

#[derive(Debug)]
struct UseInterval {
    start: UsePosition,
    end: UsePosition,
}

#[derive(Debug)]
struct LiveRange {
    uses: Vec<UseInterval>,
    kind: LiveRangeKind,
}

#[derive(Debug)]
enum LiveRangeKind {
    Virtual {
        reg: VirtualReg,
        assigned: Option<MachReg>,
    },
    Fixed {
        reg: MachReg,
    }
}

impl Location {
    fn regs<'a>(&'a self) -> Box<Iterator<Item=&'a Reg> + 'a> {
        Box::new(self.regs.iter())
    }
}

impl Reg {
    fn iter<'a>(&'a self) -> Box<Iterator<Item=&'a Reg> + 'a> {
        Box::new(iter::once(self))
    }
}

impl MachOperand {
    fn reads<'a>(&'a self) -> impl Iterator<Item=&'a Reg> + 'a {
        match self {
            &MachOperand::Read(ref r) => {
                reg.iter()
            }
            &MachOperand::Write(ref w) => {
            }
        }
    }

    fn writes<'a>(&'a self) -> impl Iterator<Item=&'a Reg> + 'a {
    }

    fn regs<'a>(&'a self) -> impl Iterator<Item=&'a Reg> + 'a {
    }
}

impl MachInstr {
    fn ops(&self) -> &[MachOperand] {
        &self.ops
    }

    fn regs<'a>(&'a self) -> impl Iterator<Item=&Reg> + 'a {
        self.ops.iter().flat_map(|op| op.regs())
    }
}

impl fmt::Debug for VirtualReg {
    fn fmt(&self, fmt: &mut fmt::Formatter) -> fmt::Result {
        write!(fmt, "%v{}", self.0)
    }
}

impl fmt::Debug for MachReg {
    fn fmt(&self, fmt: &mut fmt::Formatter) -> fmt::Result {
        write!(fmt, "%r{}", self.0)
    }
}


impl LiveRangeKind {
    fn new(reg: &Reg) -> Self {
        match reg {
            &Reg::Virtual(v) => {
                LiveRangeKind::Virtual {
                    reg: v,
                    assigned: None,
                }
            }
            &Reg::Fixed(r) => {
                LiveRangeKind::Fixed {
                    reg: r,
                }
            }
        }
    }

    fn reg(&self) -> Reg {
        match self {
            &LiveRangeKind::Virtual { reg, .. } => Reg::Virtual(reg),
            &LiveRangeKind::Fixed { reg } => Reg::Fixed(reg),
        }
    }
}

impl LiveRange {
    fn new(reg: &Reg) -> Self {
        LiveRange {
            uses: vec![],
            kind: LiveRangeKind::new(reg),
        }
    }

    fn add_use(&mut self, instr_ix: usize, instr: &MachInstr) {
    }

    fn is_for(&self, r: &Reg) -> bool {
        &self.kind.reg() == r
    }

    fn reg(&self) -> Reg {
        self.kind.reg()
    }
}

fn find_or_create_live_range(ranges: &mut Vec<LiveRange>, r: &Reg) -> usize {
    if let Some(ix) = ranges.iter_mut().position(|rg| rg.is_for(r)) {
        return ix;
    }

    let r = LiveRange::new(r);
    ranges.push(r);
    ranges.len() - 1
}

fn add_use_to_live_range(ranges: &mut Vec<LiveRange>, reg: &Reg,
                         instr_ix: usize, instr: &MachInstr) {
    let range_ix = find_or_create_live_range(ranges, reg);
    // Maybe also add hint.
    ranges[range_ix].add_use(instr_ix, instr);
}

#[derive(Debug)]
struct RegAllocInfo {
    instrs: Vec<MachInstr>,
    available_regs: Vec<MachReg>,
}

// 1: Build live ranges.
fn build_live_ranges(rai: &mut RegAllocInfo) -> Vec<LiveRange> {
    let mut ranges = vec![];
    
    for (instr_ix, instr) in rai.instrs.iter().enumerate() {
        for reg in instr.regs() {
            add_use_to_live_range(&mut ranges, reg, instr_ix, instr);
        }
    }

    ranges
}


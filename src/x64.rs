use std::fmt;

#[derive(Debug, Clone, Eq, PartialEq)]
pub enum Operand {
    Reg(Reg),
    Mem(Mem),
    Imm(Imm),
}

#[derive(Debug, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum RegLocInInstr {
    Dst(RegLocInOp),
    Src(RegLocInOp),
}

#[derive(Debug, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum RegLocInOp {
    Reg,
    MemBase,
    MemIndex,
}

pub type Imm = u32;

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct Mem {
    pub base: Reg,
    pub index: Option<Reg>,
    pub disp: u32,
}

#[derive(Debug, Hash, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum Reg {
    Virtual(VirtualReg),
    Mach(MachReg),
}

#[derive(Hash, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct VirtualReg(pub u32);
#[derive(Hash, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct MachReg(pub u32);

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct Instr {
    pub opcode: OpCode,
    pub ops: Vec<Operand>,
    pub parallel_moves: ParallelMoves,
}

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct ParallelMoves {
    start: Vec<ParallelMove>,
    end: Vec<ParallelMove>,
}

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct ParallelMove {
    dst: Operand,
    src: Operand,
}

#[derive(Debug, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum OpCode {
    Add,
    Mov,
    Ret,
}

#[derive(Debug)]
pub struct Block {
    pub instrs: Vec<Instr>,
}

// `Zipper` to blocks[id].instrs[id].use_kind[ix]
#[derive(Debug, Clone, Eq, PartialEq)]
pub struct RegContext {
    pub reg: Reg,
    pub kind: UseKind,
    pub block_id: u32,
    pub instr_ix: u32,
    pub operand_ix: RegLocInInstr,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Ord, PartialOrd)]
pub enum UseKind {
    Input,
    Output,
}

// Impls

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

impl Block {
    pub fn new(instrs: Vec<Instr>) -> Self {
        Block { instrs }
    }

    pub fn instrs(&self) -> &[Instr] {
        &self.instrs
    }
}

impl OpCode {
    pub fn has_dst(self) -> bool {
        match self {
            OpCode::Add | OpCode::Mov => true,
            OpCode::Ret => false,
        }
    }

    pub fn reads_dst(self) -> bool {
        match self {
            OpCode::Add => true,
            OpCode::Mov => false,
            OpCode::Ret => false,
        }
    }
}

impl Operand {
    pub fn new_mach_reg(ix: u32) -> Self {
        Operand::Reg(Reg::new_mach(ix))
    }

    pub fn new_virt_reg(ix: u32) -> Self {
        Operand::Reg(Reg::new_virt(ix))
    }
}

impl MachReg {
    pub fn new(ix: usize) -> Self {
        MachReg(ix as u32)
    }

    pub fn into_reg(self) -> Reg {
        Reg::Mach(self)
    }

    pub fn ix(self) -> usize {
        self.0 as usize
    }
}

impl Reg {
    pub fn new_mach(ix: u32) -> Self {
        Reg::Mach(MachReg(ix))
    }

    pub fn is_mach(&self) -> bool {
        match self {
            &Reg::Mach(_) => true,
            _ => false,
        }
    }

    pub fn mach_ix(&self) -> usize {
        match self {
            &Reg::Mach(m) => m.ix(),
            _ => panic!("{:?} is not a MachReg", self),
        }
    }

    pub fn virt_ix(&self) -> u32 {
        match self {
            &Reg::Virtual(v) => v.0,
            _ => panic!("{:?} is not a VirtualReg", self),
        }
    }

    pub fn rsp() -> Self {
        // FIXME
        Reg::new_mach(100)
    }

    pub fn new_virt(ix: u32) -> Self {
        Reg::Virtual(VirtualReg(ix))
    }

    pub fn into_op(self) -> Operand {
        Operand::Reg(self)
    }
}

impl Instr {
    pub fn new(opcode: OpCode, ops: Vec<Operand>) -> Self {
        Self { opcode, ops, parallel_moves: ParallelMoves::new() }
    }

    pub fn new2(opcode: OpCode, dst: Operand, src: Operand) -> Self {
        Self::new(opcode, vec![dst, src])
    }

    pub fn new1(opcode: OpCode, op: Operand) -> Self {
        Self::new(opcode, vec![op])
    }

    pub fn add(dst: Operand, src: Operand) -> Self {
        Self::new2(OpCode::Add, dst, src)
    }

    pub fn mov(dst: Operand, src: Operand) -> Self {
        Self::new2(OpCode::Mov, dst, src)
    }

    pub fn ret(op: Operand) -> Self {
        Self::new1(OpCode::Ret, op)
    }

    fn dst(&self) -> Option<&Operand> {
        if self.opcode.has_dst() {
            Some(&self.ops[0])
        } else {
            None
        }
    }

    fn dst_mut(&mut self) -> Option<&mut Operand> {
        if self.opcode.has_dst() {
            Some(&mut self.ops[0])
        } else {
            None
        }
    }

    fn src(&self) -> &Operand {
        let ix = if self.opcode.has_dst() {
            1
        } else {
            0
        };

        &self.ops[ix]
    }

    fn src_mut(&mut self) -> &mut Operand {
        let ix = if self.opcode.has_dst() {
            1
        } else {
            0
        };

        &mut self.ops[ix]
    }

    pub fn set_reg_at(&mut self, ix: &RegLocInInstr, r: Reg) {
        match ix {
            &RegLocInInstr::Dst(ref dst) => self.dst_mut().unwrap().set_reg_at(dst, r),
            &RegLocInInstr::Src(ref src) => self.src_mut().set_reg_at(src, r),
        }
    }

    pub fn outputs(&self) -> Option<(RegLocInInstr, Reg)> {
        self.dst().and_then(|op| match op {
            &Operand::Reg(r) => Some((RegLocInInstr::Dst(RegLocInOp::Reg), r)),
            _ => None,
        })
    }

    pub fn inputs(&self) -> Vec<(RegLocInInstr, Reg)> {
        let mut srcs = vec![];
        match self.src() {
            &Operand::Reg(r) => { srcs.push((RegLocInOp::Reg, r)); }
            &Operand::Mem(ref m) => { srcs.extend(m.regs()); }
            _ => (),
        }
        let mut dsts = vec![];
        match self.dst() {
            Some(&Operand::Reg(r)) => {
                if self.opcode.reads_dst() {
                    dsts.push((RegLocInOp::Reg, r));
                }
            }
            Some(&Operand::Mem(ref m)) => {
                dsts.extend(m.regs())
            }
            _ => (),
        }
        srcs.into_iter().map(|(loc, r)| (RegLocInInstr::Src(loc), r))
            .chain(dsts.into_iter().map(|(loc, r)| (RegLocInInstr::Dst(loc), r)))
            .collect()
    }
}

impl ParallelMoves {
    fn new() -> Self {
        Self {
            start: vec![],
            end: vec![],
        }
    }

    pub fn add_to_start(&mut self, mov: ParallelMove) {
        self.start.push(mov);
    }

    pub fn add_to_end(&mut self, mov: ParallelMove) {
        self.end.push(mov);
    }
}

impl ParallelMove {
    pub fn new(dst: Operand, src: Operand) -> Self {
        Self { dst, src }
    }
}

impl Operand {
    fn set_reg_at(&mut self, ix: &RegLocInOp, to_r: Reg) {
        match (self, ix) {
            (&mut Operand::Reg(ref mut r), &RegLocInOp::Reg) => *r = to_r,
            (&mut Operand::Mem(ref mut m), &RegLocInOp::MemBase) => m.base = to_r,
            (&mut Operand::Mem(ref mut m), &RegLocInOp::MemIndex) => {
                *(m.index.as_mut().unwrap()) = to_r;
            }
            (thiz, _) => panic!("No such position {:?} on {:?}", ix, thiz),
        }
    }
}

impl Mem {
    pub fn regs(&self) -> Vec<(RegLocInOp, Reg)> {
        let mut rs = vec![(RegLocInOp::MemBase, self.base)];
        rs.extend(self.index.iter().map(|r| (RegLocInOp::MemIndex, *r)));
        rs
    }

    pub fn into_op(self) -> Operand {
        Operand::Mem(self)
    }
}

impl RegContext {
    pub fn new(reg: Reg, kind: UseKind,
               block_id: usize, instr_ix: usize, operand_ix: RegLocInInstr) -> Self {
        RegContext {
            reg,
            kind,
            block_id: block_id as u32,
            instr_ix: instr_ix as u32,
            operand_ix,
        }
    }

    pub fn new_input(reg: Reg, block_id: usize,
                     instr_ix: usize, operand_ix: RegLocInInstr) -> Self {
        RegContext::new(reg, UseKind::Input, block_id, instr_ix, operand_ix)
    }

    pub fn new_output(reg: Reg, block_id: usize,
                      instr_ix: usize, operand_ix: RegLocInInstr) -> Self {
        RegContext::new(reg, UseKind::Output, block_id, instr_ix, operand_ix)
    }

    pub fn as_input(&self) -> Self {
        RegContext { kind: UseKind::Input, ..self.clone() }
    }
}

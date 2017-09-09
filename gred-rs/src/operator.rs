use node::{Id, Node};
use graph::{Graph, Reducer, Reduction};

#[derive(Debug, Clone)]
pub enum Arith {
    Add,
    IntLit(isize),
}

pub struct ArithSimplifier;

impl Reducer<Arith> for ArithSimplifier {
    fn reduce(&self, g: &Graph<Arith>, n: Id) -> Reduction {
        if let Some(n) = g.node_at(n) {
            Reduction::Unchanged
        } else {
            Reduction::Unchanged
        }
    }
}

use std::collections::LinkedList;

pub type Id = u32;

#[derive(Debug, Clone)]
pub struct Node<A> {
    id: Id,
    op: A,
    inputs: Vec<Id>,
    uses: LinkedList<Use>,
}

impl<A> Node<A> {
    pub fn new(id: Id, op: A) -> Self {
        Node {
            id,
            op,
            inputs: vec![],
            uses: LinkedList::new(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Use {
    input_ix: usize,
    target: Id,
}
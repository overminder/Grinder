use std::collections::{HashMap, HashSet};
use std::cell::{Cell, RefCell};
use std::iter::{self, FromIterator};

use ordermap::OrderMap;

use node::{Id, Node, Use};

pub struct Graph<A> {
    id: Cell<Id>,
    nodes: HashMap<Id, NodeRef<A>>,
}

type NodeRef<A> = RefCell<Node<A>>;

impl<A> Graph<A> {
    pub fn new() -> Self {
        Graph {
            id: Cell::new(1),
            nodes: HashMap::new(),
        }
    }

    pub fn add_node(&mut self, op: A) -> Id {
        let id = self.id.get();
        self.id.set(id + 1);
        self.nodes.insert(id, RefCell::new(Node::new(id, op)));
        id
    }

    pub fn reduce_all<R: Reducer<A>>(&self, r: R) {
        let mut changed: OrderMap<Id, ()> = {
            self.nodes.keys().cloned().zip(iter::repeat(())).collect()
        };
        loop {
            if let Some((id, _)) = changed.pop() {
                match r.reduce(self, id) {
                    Reduction::Changed(id) => {
                        changed.insert(id, ());
                    }
                    Reduction::Unchanged => {
                        continue;
                    }
                }
            } else {
                break;
            }
        }
    }

    pub fn node_at(&self, id: Id) -> Option<&NodeRef<A>> {
        self.nodes.get(&id)
    }
}

#[derive(Debug, Clone)]
pub enum Reduction {
    Changed(Id),
    Unchanged,
}

pub trait Reducer<A> {
    fn reduce(&self, g: &Graph<A>, n: Id) -> Reduction;
}

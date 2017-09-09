use std::mem;

#[derive(Debug)]
pub struct Graph {
    nodes: Vec<Node>,
}

#[derive(Debug, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct Id(u32);

#[derive(Debug, Clone)]
pub struct Node {
    id: Id,
    op: Operator,
    uses: Vec<Use>,
    inputs: Vec<Id>,
}

#[derive(Debug, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum Operator {
    Int64Add,
    Int64Constant(i64),
    Branch,
    Return,
    Phi,
    Merge,
    Dead,
}

#[derive(Debug, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub enum NodeView {
    Int64Add(Id, Id),
    Int64Constant(i64),
    Return(Id),
    Branch(Id),
    Phi {
        merge: Id,
        value_inputs: Vec<Id>,
    },
    Merge(Vec<Id>), // Control inputs
    Dead,
}

#[derive(Debug, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct Use {
    // Who uses me?
    user: Id,

    // Where's me on the user's input list?
    input_ix: u32,
}

impl Graph {
    pub fn new() -> Self {
        Graph { nodes: vec![] }
    }

    fn get_node(&self, id: Id) -> &Node {
        &self.nodes[id.ix()]
    }

    fn get_node_mut(&mut self, id: Id) -> &mut Node {
        &mut self.nodes[id.ix()]
    }

    // All the public methods preserve node invariants.

    pub fn add_node(&mut self, op: Operator) -> Id {
        let id = Id::new(self.nodes.len());
        let n = Node::new(id, op);
        self.nodes.push(n);
        id
    }

    // XXX: Moving things around might break saved Ids.
    pub fn remove_dead_node(&mut self, id: Id) {
        debug_assert!(self.get_node(id).is_dead());
        let last_id = Id::new(self.nodes.len() - 1);
        if last_id != id {
            // Clone the node to id
            *self.get_node_mut(id) = self.get_node(last_id).clone();
            // TODO: Redirect self-references?
            self.replace_node(last_id, id);
        }
        self.nodes.pop();
    }

    fn remove_node_simple(&mut self, id: Id) -> Node {
        mem::replace(self.get_node_mut(id), Node::new_dead(id))
    }

    pub fn replace_node(&mut self, from: Id, to: Id) {
        let removed = self.remove_node_simple(from);
        // remove.uses.each |u|
        //     u.user.inputs[u.input_ix] = to
        //     to.uses.push(u)
        for u in removed.uses() {
            if u.user() == from {
                // Self-use: I'm already killed, no need to replace this one.
                continue;
            }
            self.replace_input(*u, from, to);
        }

        // remove.inputs.each |ix, i|
        //     i.uses.remove(i.uses.position(Use(from, ix)))
        for (i, u) in removed.inputs_as_uses() { // removed.inputs().iter().enumerate() {
            self.remove_use_simple(i, u);
        }
    }

    pub fn view_node(&self, n: Id) -> NodeView {
        let n = self.get_node(n);
        n.op.view(n)
    }

    pub fn add_input(&mut self, user: Id, input: Id) {
        let input_ix = self.get_node_mut(user).add_input(input);
        self.get_node_mut(input).add_use(Use::new(user, input_ix));
    }

    fn replace_input(&mut self, u: Use, from: Id, to: Id) {
        let replaced = self.get_node_mut(u.user()).replace_input(u.input_ix(), to);
        self.get_node_mut(to).add_use(u);
        debug_assert!(replaced == from);
    }

    fn remove_use_simple(&mut self, n: Id, u: Use) {
        self.get_node_mut(n).remove_use(u);
    }

    pub fn verify_all_nodes(&self) -> bool {
        for n in &self.nodes {
            self.verify_node(n.id);
        }
        true
    }

    // Debug's use
    pub fn verify_node(&self, n: Id) -> bool {
        if let Some(n) = self.nodes.get(n.ix()) {
            n.verify()
        } else {
            panic!("{:?} not found", n)
        }
    }

    pub fn num_uses(&self, n: Id) -> usize {
        self.get_node(n).uses().len()
    }
}

impl Id {
    fn new(v: usize) -> Self {
        Id(v as u32)
    }

    fn ix(self) -> usize {
        self.0 as usize
    }
}


impl Node {
    fn new(id: Id, op: Operator) -> Self {
        Node { id, op, uses: vec![], inputs: vec![] }
    }

    fn new_dead(id: Id) -> Self {
        Self::new(id, Operator::Dead)
    }

    fn is_dead(&self) -> bool {
        self.op.is_dead()
    }

    fn inputs_as_uses<'a>(&'a self) -> impl Iterator<Item=(Id, Use)> + 'a {
        self.inputs.iter()
            .enumerate()
            .map(move |(ix, i)| (*i, Use::new(self.id, ix)))
    }

    fn inputs(&self) -> &[Id] {
        &self.inputs
    }

    fn uses(&self) -> &[Use] {
        &self.uses
    }

    fn add_input(&mut self, input: Id) -> usize {
        let ix = self.inputs.len();
        self.inputs.push(input);
        ix
    }

    fn add_use(&mut self, u: Use) {
        self.uses.push(u);
    }

    // Node methods are mostly primitive: they don't try to preserve
    // the graph invariants.

    fn remove_use(&mut self, u: Use) {
        if let Some(ix) = self.uses.iter().position(|x| *x == u) {
            self.uses.swap_remove(ix);
        }
    }

    fn replace_input(&mut self, ix: usize, to: Id) -> Id {
        mem::replace(&mut self.inputs[ix], to)
    }

    fn verify(&self) -> bool {
        if let Some(num_inputs) = self.op.num_inputs() {
            assert!(num_inputs == self.inputs.len());
        }
        true
    }
}

impl Operator {
    fn is_dead(&self) -> bool {
        use self::Operator::*;
        match self {
            &Dead => true,
            _ => false,
        }
    }

    fn num_inputs(&self) -> Option<usize> {
        use self::Operator::*;
        let n = match self {
            &Int64Add => 2,
            &Int64Constant(_) => 0,
            &Branch => 1,
            &Return => 1,
            &Phi => return None,
            &Merge => return None,
            &Dead => 0,
        };
        Some(n)
    }

    // XXX: This can be pretty expensive.
    fn view(&self, n: &Node) -> NodeView {
        use self::Operator::*;
        debug_assert!(n.verify());
        let i = &n.inputs;
        match self {
            &Int64Add => NodeView::Int64Add(i[0], i[1]),
            &Int64Constant(i) => NodeView::Int64Constant(i),
            &Branch => NodeView::Branch(i[0]),
            &Return => NodeView::Return(i[0]),
            &Phi => NodeView::Phi {
                merge: i[0],
                value_inputs: i[1..].to_vec(),
            },
            &Merge => NodeView::Merge(i.to_vec()),
            &Dead => NodeView::Dead,
        }
    }
}


impl Use {
    fn new(user: Id, input_ix: usize) -> Self {
        Use { user, input_ix: input_ix as u32 }
    }

    fn user(self) -> Id {
        return self.user
    }

    fn input_ix(self) -> usize {
        self.input_ix as usize
    }
}

#[cfg(test)]
mod test {
    use super::*;

    fn mkg() -> Graph {
        Graph::new()
    }

    #[test]
    fn graph_generates_unique_ids() {
        let mut g = mkg();
        let n1 = g.add_node(Operator::Int64Constant(0));
        let n2 = g.add_node(Operator::Int64Constant(1));
        assert!(n1 != n2)
    }

    #[test]
    fn graph_can_link_nodes() {
        let mut g = mkg();
        let a1 = g.add_node(Operator::Int64Add);
        let c1 = g.add_node(Operator::Int64Constant(40));
        let c2 = g.add_node(Operator::Int64Constant(2));
        g.add_input(a1, c1);
        g.add_input(a1, c2);

        assert_eq!(g.view_node(a1), NodeView::Int64Add(c1, c2));

        for n in [a1, c1, c2].iter() {
            g.verify_node(*n);
        }
    }

    #[test]
    fn graph_reduction_works_without_self_ref() {
        let mut g = mkg();
        let a1 = g.add_node(Operator::Int64Add);
        let a2 = g.add_node(Operator::Int64Add);
        let c1 = g.add_node(Operator::Int64Constant(40));
        let c2 = g.add_node(Operator::Int64Constant(2));
        let c3 = g.add_node(Operator::Int64Constant(42));

        g.add_input(a1, c1);
        g.add_input(a1, c2);
        g.add_input(a2, a1);
        g.add_input(a2, a1);

        g.replace_node(a1, c3);
        assert_eq!(g.view_node(a1), NodeView::Dead);
        assert_eq!(g.view_node(a2), NodeView::Int64Add(c3, c3));
        assert_eq!(g.num_uses(c1), 0);
        assert_eq!(g.num_uses(c2), 0);
        assert_eq!(g.num_uses(c3), 2);
    }

    #[test]
    fn graph_reduction_can_kill_nodes_with_self_ref() {
        let mut g = mkg();
        let a1 = g.add_node(Operator::Int64Add);
        let a2 = g.add_node(Operator::Int64Add);
        let c1 = g.add_node(Operator::Int64Constant(40));
        let c3 = g.add_node(Operator::Int64Constant(42));

        g.add_input(a1, c1);
        g.add_input(a1, a1);
        g.add_input(a2, a1);
        g.add_input(a2, a1);

        g.replace_node(a1, c3);
        assert_eq!(g.view_node(a1), NodeView::Dead);
        assert_eq!(g.view_node(a2), NodeView::Int64Add(c3, c3));
        assert_eq!(g.num_uses(c1), 0);
        assert_eq!(g.num_uses(c3), 2);
    }

    #[test]
    fn graph_reduction_can_introduce_self_ref() {
        let mut g = mkg();
        let a1 = g.add_node(Operator::Int64Add);
        let c1 = g.add_node(Operator::Int64Constant(2));
        let c2 = g.add_node(Operator::Int64Constant(40));

        g.add_input(a1, c1);
        g.add_input(a1, c2);
        g.replace_node(c2, a1);

        assert_eq!(g.view_node(c2), NodeView::Dead);
        assert_eq!(g.view_node(a1), NodeView::Int64Add(c1, a1));
        assert_eq!(g.num_uses(c2), 0);
        assert_eq!(g.num_uses(a1), 1);
    }
}

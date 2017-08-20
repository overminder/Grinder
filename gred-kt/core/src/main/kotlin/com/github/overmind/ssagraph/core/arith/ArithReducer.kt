package com.github.overmind.ssagraph.core.arith

import com.github.overmind.ssagraph.core.Graph
import com.github.overmind.ssagraph.core.Id
import com.github.overmind.ssagraph.core.Node
import com.github.overmind.ssagraph.core.Reduction

object ArithReducer : Graph.Reducer<ArithOp> {
    override fun reduce(g: Graph<ArithOp>, id: Id): Reduction = ArithReducerImpl(g).reduce(id)
}

private class ArithReducerImpl(val g: AGraph) {
    @Suppress("UNCHECKED_CAST")
    fun reduce(id: Id): Reduction {
        val n = g.nodeAt(id)
        return when (n.op) {
            is BinaryArithOp -> reduceBinary(n as Node<BinaryArithOp>)
            is Mov -> reduceMov(n as Node<Mov>)
            is If -> reduceIf(n as Node<If>)
            is InlineCall -> reduceInlineCall(n as Node<InlineCall>)
            else -> Reduction.Unchanged
        }
    }

    private fun reduceInlineCall(node: Node<InlineCall>): Reduction {
        assert(node.inputs.size == node.op.body.argc)

        val mapping = mutableMapOf<Id, Id>()
        val edit = g.edit()
        val body = node.op.body
        fun inlineNode(from: Id): Id {
            // If already mapped: return it
            mapping[from]?.let { return it }
            val fromNode = body.nodeAt(from)
            fromNode.op.let {
                if (it is Argument) {
                    // If is argument: map to node input
                    return node.inputs[it.ix]
                }
            }
            // Else: build a new node
            val to = edit.addNode(fromNode.op)
            mapping[from] = to
            fromNode.inputs.forEach {
                edit.addInput(inlineNode(it), to)
            }
            return to
        }
        val retVal = inlineNode(body.retValNode.id)
        return Reduction.Changed(retVal)
    }

    private fun reduceIf(n: Node<If>): Reduction {
        if (n.t == n.f) {
            return Reduction.Changed(n.t)
        }

        val cond= g.checkOp(IntLit::class.java, n.cond) ?: return Reduction.Unchanged
        return if (cond.op.value != 0) {
            Reduction.Changed(n.t)
        } else {
            Reduction.Changed(n.f)
        }
    }

    private fun reduceBinary(n: Node<BinaryArithOp>): Reduction {
        val lhs = g.checkOp(IntLit::class.java, n.lhs) ?: return Reduction.Unchanged
        val rhs = g.checkOp(IntLit::class.java, n.rhs) ?: return Reduction.Unchanged
        return Reduction.Changed(g.edit().addNode(IntLit(n.op.eval(lhs.op.value, rhs.op.value))))
    }

    private fun reduceMov(n: Node<Mov>): Reduction {
        return Reduction.Changed(n.from)
    }
}
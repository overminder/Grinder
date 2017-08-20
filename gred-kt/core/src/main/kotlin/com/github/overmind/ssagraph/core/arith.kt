package com.github.overmind.ssagraph.core

sealed class ArithOp {
    object Add: ArithOp() {
        override fun toString() = "Add"
    }
    data class IntLit(val value: Int): ArithOp()
    object Mov: ArithOp() {
        override fun toString() = "Mov"
    }
    object End: ArithOp() {
        override fun toString() = "End"
    }

    object Reducer: Graph.Reducer<ArithOp> {
        override fun reduce(g: Graph<ArithOp>, id: Id): Reduction {
            val n = g.nodeAt(id)
            return when (n.op) {
                is Add -> reduceAdd(g, n as Node<Add>)
                else -> Reduction.Unchanged
            }
        }

        private fun reduceAdd(g: Graph<ArithOp>, n: Node<Add>): Reduction {
            val lhs = g.checkOp(IntLit::class.java, n.lhs()) ?: return Reduction.Unchanged
            val rhs = g.checkOp(IntLit::class.java, n.rhs()) ?: return Reduction.Unchanged
            return Reduction.Changed(g.edit().newNode(IntLit(lhs.op.value + rhs.op.value)))
        }
    }
}

fun Node<ArithOp.Add>.lhs() = inputs[0]
fun Node<ArithOp.Add>.rhs() = inputs[1]

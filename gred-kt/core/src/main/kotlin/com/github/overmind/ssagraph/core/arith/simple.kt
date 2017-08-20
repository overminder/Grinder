package com.github.overmind.ssagraph.core.arith

import com.github.overmind.ssagraph.core.Graph
import com.github.overmind.ssagraph.core.Id
import com.github.overmind.ssagraph.core.Node
import com.github.overmind.ssagraph.core.Reduction

typealias AGraph = Graph<ArithOp>
typealias ANode = Node<ArithOp>

sealed class ArithOp {
    object Reducer: Graph.Reducer<ArithOp> {
        @Suppress("UNCHECKED_CAST")
        override fun reduce(g: Graph<ArithOp>, id: Id): Reduction {
            val n = g.nodeAt(id)
            return when (n.op) {
                is BinaryArithOp -> reduceBinary(g, n as Node<BinaryArithOp>)
                is Mov -> reduceMov(n as Node<Mov>)
                else -> Reduction.Unchanged
            }
        }

        private fun reduceBinary(g: Graph<ArithOp>, n: Node<BinaryArithOp>): Reduction {
            val lhs = g.checkOp(IntLit::class.java, n.lhs) ?: return Reduction.Unchanged
            val rhs = g.checkOp(IntLit::class.java, n.rhs) ?: return Reduction.Unchanged
            return Reduction.Changed(g.edit().addNode(IntLit(n.op.eval(lhs.op.value, rhs.op.value))))
        }

        private fun reduceMov(n: Node<Mov>): Reduction {
            return Reduction.Changed(n.from)
        }
    }
}

sealed class BinaryArithOp(private val name: String, internal val eval: (Int, Int) -> Int): ArithOp() {
    override fun toString() = name
}

data class IntLit(val value: Int): ArithOp()
data class Argument(val ix: Int): ArithOp()
object Mov: ArithOp() {
    override fun toString() = "Mov"
}
object End: ArithOp() {
    override fun toString() = "End"
}

object Add: BinaryArithOp("Add", { a, b -> a + b })
object Sub: BinaryArithOp("Sub", { a, b -> a - b })

val Node<BinaryArithOp>.lhs
    get() = inputs[0]
val Node<BinaryArithOp>.rhs
    get() = inputs[1]
val Node<Mov>.from
    get() = inputs[0]
val AGraph.endNode
    get() = findOp(End::class.java).asSequence().single()
val AGraph.retValNode
    get() = nodeAt(endNode.inputs.single())

class Interpreter(val g: AGraph, val args: IntArray) {
    fun eval(): Int {
        return eval(g.endNode)
    }

    fun eval(id: Id) = eval(g.nodeAt(id))

    @Suppress("UNCHECKED_CAST")
    fun eval(n: ANode): Int = when (n.op) {
        is BinaryArithOp -> evalBinary(n as Node<BinaryArithOp>)
        is Mov -> evalMov(n as Node<Mov>)
        is IntLit -> n.op.value
        is Argument -> args[n.op.ix]
        is End -> eval(n.inputs.single())
        // else -> throw RuntimeException("Not a value node: $n")
    }

    private fun evalMov(node: Node<Mov>): Int = eval(node.from)

    private fun evalBinary(node: Node<BinaryArithOp>): Int {
        val lhs = eval(node.lhs)
        val rhs = eval(node.rhs)
        return node.op.eval(lhs, rhs)
    }
}


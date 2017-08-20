package com.github.overmind.ssagraph.core.arith

import com.github.overmind.ssagraph.core.*
import java.util.*

typealias AGraph = Graph<ArithOp>
typealias AGraphEditor = GraphEditor<ArithOp>
typealias ANode = Node<ArithOp>

data class ArithIdentity(val op: ArithOp, val inputs: IntArray) {
    // Automatically generated.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArithIdentity

        if (op != other.op) return false
        if (!Arrays.equals(inputs, other.inputs)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = op.hashCode()
        result = 31 * result + Arrays.hashCode(inputs)
        return result
    }
}

sealed class ArithOp: Operator {
    override fun cacheKey(inputs: IntArray) = ArithIdentity(this, inputs)
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

data class InlineCall(val body: AGraph): ArithOp()

object Add: BinaryArithOp("Add", { a, b -> a + b })
object Sub: BinaryArithOp("Sub", { a, b -> a - b })
object Lt: BinaryArithOp("Lt", { a, b -> if (a < b) 1 else 0 })

object If: ArithOp() {
    override fun toString(): String = "If"
}

val Node<BinaryArithOp>.lhs
    get() = inputs[0]
val Node<BinaryArithOp>.rhs
    get() = inputs[1]
val Node<Mov>.from
    get() = inputs[0]
val Node<If>.cond
    get() = inputs[0]
val Node<If>.t
    get() = inputs[1]
val Node<If>.f
    get() = inputs[2]
val AGraph.endNode
    get() = findOp(End::class.java).asSequence().single()
val AGraph.retValNode
    get() = nodeAt(endNode.inputs.single())

fun mkG(block: AGraphEditor.() -> Id): AGraph {
    val g = AGraph()
    val end = g.edit().addNode(End)
    val res = block(g.edit())
    g.edit().addInput(res, end)
    return g
}

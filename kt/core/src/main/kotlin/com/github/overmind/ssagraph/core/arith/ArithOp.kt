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

data class ArithProperties(val valueIn: Int,
                           val controlIn: Int = 0,
                           val valueOut: Int = 1,
                           val controlOut: Int = 0,
                           internal val props: List<Prop> = emptyList()) {
    private val fastProps = props.toSet()
    val pure
        get() = fastProps.contains(Prop.Pure)

    enum class Prop {
        Pure,
    }
}

sealed class ArithOp: Operator {
    override fun cacheKey(inputs: IntArray): ArithIdentity? {
        return if (props.pure && inputs.size == props.valueIn) {
            ArithIdentity(this, inputs)
        } else {
            // Partial or impure nodes are not cacheable.
            null
        }
    }
    abstract val props: ArithProperties
}

sealed class BinaryArithOp(private val name: String, internal val eval: (Int, Int) -> Int): ArithOp() {
    override fun toString() = name
    override val props = ArithProperties(2, props = listOf(ArithProperties.Prop.Pure))
}

sealed class NullaryArithOp: ArithOp() {
}

sealed class UnaryArithOp: ArithOp() {
}

// Pure value / computations
data class IntLit(val value: Int): NullaryArithOp() {
    override val props = ArithProperties(0, props = listOf(ArithProperties.Prop.Pure))
}
object Add: BinaryArithOp("Add", { a, b -> a + b })
object Sub: BinaryArithOp("Sub", { a, b -> a - b })
object Lt: BinaryArithOp("Lt", { a, b -> if (a < b) 1 else 0 })
object Mov: UnaryArithOp() {
    override fun toString() = "Mov"
    override val props = ArithProperties(1, props = listOf(ArithProperties.Prop.Pure))
}

// The value input needs to be a Start.
data class Argument(val ix: Int, val debugName: String = ""): NullaryArithOp() {
    override val props = ArithProperties(1, valueOut = 1)
}

// Call
data class KnownApply(val body: AGraph): ArithOp() {
    override val props: ArithProperties
        get() = ArithProperties(body.argc)
}

// Graph start / end
data class Start(internal val valueOut: Int): ArithOp() {
    override val props = ArithProperties(valueIn = 0, controlIn = 0, valueOut = valueOut, controlOut = 1)
}

object End: UnaryArithOp() {
    override fun toString() = "End"
    override val props = ArithProperties(1)
}

// Branches
object Branch: ArithOp() {
    override fun toString() = "Branch"
    override val props = ArithProperties(valueIn = 1, controlIn = 1, valueOut = 0, controlOut = 2)
}

object IfTrue: ArithOp() {
    override fun toString() = "IfTrue"
    override val props = ArithProperties(valueIn = 0, controlIn = 1, valueOut = 0, controlOut = 1)
}

object IfFalse: ArithOp() {
    override fun toString() = "IfFalse"
    override val props = ArithProperties(valueIn = 0, controlIn = 1, valueOut = 0, controlOut = 1)
}

// The ternary operator, v8 calls this `Select`.
object Select: ArithOp() {
    override fun toString(): String = "Select"
    override val props = ArithProperties(3)
}

// Phi / merge

data class Phi(internal val valueIn: Int): ArithOp() {
    override val props = ArithProperties(valueIn, 1)
}

val Node<BinaryArithOp>.lhs
    get() = inputs[0]
val Node<BinaryArithOp>.rhs
    get() = inputs[1]
val Node<Mov>.from
    get() = inputs[0]
val Node<Select>.cond
    get() = inputs[0]
val Node<Select>.t
    get() = inputs[1]
val Node<Select>.f
    get() = inputs[2]
val AGraph.endNode
    get() = findOp(End::class.java).asSequence().single()
val AGraph.retValNode
    get() = nodeAt(endNode.inputs.single())

fun mkG(argc: Int = 0, block: AGraphEditor.() -> Id): AGraph {
    val g = AGraph(argc = argc)
    val end = g.edit().addNode(End)
    val res = block(g.edit())
    g.edit().addInput(res, end)
    return g
}

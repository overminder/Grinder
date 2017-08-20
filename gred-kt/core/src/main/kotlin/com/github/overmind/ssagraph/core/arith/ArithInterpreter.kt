package com.github.overmind.ssagraph.core.arith

import com.github.overmind.ssagraph.core.Id
import com.github.overmind.ssagraph.core.Node

data class Evaluation(val result: Int, val reductions: Int)

class ArithInterpreter(val g: AGraph, val args: IntArray) {
    init {
        assert(g.argc == args.size)
    }

    fun eval(): Evaluation {
        val impl = ArithInterpreterImpl(this)
        val res = impl.eval()
        return Evaluation(res, impl.reductions)
    }
}

private class ArithInterpreterImpl(top: ArithInterpreter) {
    val g = top.g
    val args = top.args
    val vregs = mutableMapOf<Id, Int>()

    var reductions = 0

    fun eval(): Int {
        return eval(g.endNode.id)
    }

    fun eval(id: Id) = readFromCacheOrEval(g.nodeAt(id))

    fun readFromCacheOrEval(n: ANode): Int = vregs[n.id] ?: evalAndSaveToCache(n)

    fun evalAndSaveToCache(n: ANode): Int {
        reductions += 1
        val res = evalFully(n)
        vregs[n.id] = res
        return res
    }

    @Suppress("UNCHECKED_CAST")
    fun evalFully(n: ANode): Int = when (n.op) {
        is BinaryArithOp -> evalBinary(n as Node<BinaryArithOp>)
        is Mov -> evalMov(n as Node<Mov>)
        is If -> evalIf(n as Node<If>)
        is IntLit -> n.op.value
        is Argument -> args[n.op.ix]
        is End -> eval(n.inputs.single())
        is InlineCall -> evalInlineCall(n as Node<InlineCall>)
    }

    private fun evalInlineCall(node: Node<InlineCall>): Int {
        val args= node.inputs.map(this::eval)
        val res = ArithInterpreter(node.op.body, args.toIntArray()).eval()
        reductions += res.reductions
        return res.result
    }

    private fun evalIf(node: Node<If>): Int {
        return if (eval(node.cond) != 0) {
            eval(node.t)
        } else {
            eval(node.f)
        }
    }

    private fun evalMov(node: Node<Mov>): Int = eval(node.from)

    private fun evalBinary(node: Node<BinaryArithOp>): Int {
        val lhs = eval(node.lhs)
        val rhs = eval(node.rhs)
        return node.op.eval(lhs, rhs)
    }
}
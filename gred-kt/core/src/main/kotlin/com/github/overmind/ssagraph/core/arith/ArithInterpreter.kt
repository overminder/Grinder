package com.github.overmind.ssagraph.core.arith

import com.github.overmind.ssagraph.core.Id
import com.github.overmind.ssagraph.core.Node

class ArithInterpreter(val g: AGraph, val args: IntArray) {
    fun eval(): Int {
        return eval(g.endNode)
    }

    fun eval(id: Id) = eval(g.nodeAt(id))

    @Suppress("UNCHECKED_CAST")
    fun eval(n: ANode): Int = when (n.op) {
        is BinaryArithOp -> evalBinary(n as Node<BinaryArithOp>)
        is Mov -> evalMov(n as Node<Mov>)
        is If -> evalIf(n as Node<If>)
        is IntLit -> n.op.value
        is Argument -> args[n.op.ix]
        is End -> eval(n.inputs.single())
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
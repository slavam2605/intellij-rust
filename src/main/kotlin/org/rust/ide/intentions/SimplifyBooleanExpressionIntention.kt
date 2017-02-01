package org.rust.ide.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.rust.ide.intentions.SimplifyBooleanExpressionIntention.UnaryOperator.*
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.*
import org.rust.lang.core.psi.util.ancestors
import org.rust.lang.core.psi.util.parentOfType

class SimplifyBooleanExpressionIntention : RsElementBaseIntentionAction<RsExpr>() {
    override fun getText() = "Simplify boolean expression"
    override fun getFamilyName() = "Simplify boolean expression"

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): RsExpr? =
            element.parentOfType<RsExpr>()
                    ?.ancestors
                    ?.takeWhile { it is RsExpr }
                    ?.map { it as RsExpr }
                    ?.findLast { isSimplifiableExpression(it) }

    private fun isSimplifiableExpression(psi: RsExpr): Boolean {
        if (psi !is RsLitExpr && psi.eval() != null) return true

        return when (psi) {
            is RsBinaryExpr -> when (psi.operatorType) {
            // short-circuit operations
                ANDAND, OROR -> psi.left.eval() != null && psi.right != null
                        || psi.left.isPure() == true && psi.right!!.isPure() == true &&
                        (psi.left.eval() != null || psi.right!!.eval() != null)
                else -> false
            }
            else -> false
        }
    }

    override fun invoke(project: Project, editor: Editor, ctx: RsExpr) {
        val value = ctx.eval()
        if (value != null) {
            ctx.replace(createPsiElement(project, value))
            return
        }
        val expr = ctx as RsBinaryExpr
        val leftVal = ctx.left.eval()
        if (leftVal != null) {
            when (expr.operatorType) {
                ANDAND -> {
                    check(leftVal)
                    expr.replace(expr.right!!)
                }
                OROR -> {
                    check(!leftVal)
                    expr.replace(expr.right!!)
                }
            }
        } else {
            val rightVal = ctx.right?.eval()
                    ?: error("Can't simplify expression")
            when (expr.operatorType) {
                ANDAND -> {
                    when (rightVal) {
                        false -> expr.replace(createPsiElement(project, false))
                        true -> expr.replace(expr.left)
                    }
                }
                OROR -> {
                    when (rightVal) {
                        false -> expr.replace(expr.left)
                        true -> expr.replace(createPsiElement(project, true))
                    }
                }
            }
        }
    }

    private fun createPsiElement(project: Project, value: Any) = RsPsiFactory(project).createExpression(value.toString())

    private fun RsExpr.eval(): Boolean? {
        return when (this) {
            is RsLitExpr ->
                (kind as? RsLiteralKind.Boolean)?.value

            is RsBinaryExpr -> when (operatorType) {
                ANDAND -> {
                    val lhs = left.eval() ?: return null
                    if (!lhs) return false
                    val rhs = right?.eval() ?: return null
                    lhs && rhs
                }
                OROR -> {
                    val lhs = left.eval() ?: return null
                    if (lhs) return true
                    val rhs = right?.eval() ?: return null
                    lhs || rhs
                }
                XOR -> {
                    val lhs = left.eval() ?: return null
                    val rhs = right?.eval() ?: return null
                    lhs xor rhs
                }
                else -> null
            }

            is RsUnaryExpr -> when (operatorType) {
                UnaryOperator.NOT -> expr?.eval()?.let { !it }
                else -> null
            }

            is RsParenExpr -> expr.eval()

            else -> null
        }
    }

    /**
     * Returns `true` if all elements are `true`, `false` if there exists
     * `false` element and `null` otherwise.
     */
    private fun <T> List<T>.allMaybe(predicate: (T) -> Boolean?): Boolean? {
        val values = map(predicate)
        val nullsTrue = values.all {
            it ?: true
        }
        val nullsFalse = values.all {
            it ?: false
        }
        return if (nullsTrue == nullsFalse) nullsTrue else null
    }

    /**
     * Check if an expression is functionally pure
     * (has no side-effects and throws no errors).
     *
     * @return `true` if the expression is pure, `false` if
     *        > it is not pure (has side-effects / throws errors)
     *        > or `null` if it is unknown.
     */
    private fun RsExpr.isPure(): Boolean? {
        return when (this) {
            is RsArrayExpr -> when (semicolon) {
                null -> exprList.allMaybe { it.isPure() }
                else -> exprList[0].isPure() // Array literal of form [expr; size],
            // size is a compile-time constant, so it is always pure
            }
            is RsStructExpr -> when (structExprBody.dotdot) {
                null -> structExprBody.structExprFieldList
                        .map { it.expr }
                        .allMaybe { it?.isPure() } // TODO: Why `it` can be null?
                else -> null // TODO: handle update case (`Point{ y: 0, z: 10, .. base}`)
            }
            is RsTupleExpr -> exprList.allMaybe { it.isPure() }
            is RsFieldExpr -> expr.isPure()
            is RsParenExpr -> expr.isPure()
            is RsBreakExpr -> false // Changes execution flow
            is RsContExpr -> false  // -//-
            is RsRetExpr -> false   // -//-
            is RsTryExpr -> false   // -//-
            is RsPathExpr -> true   // Paths are always pure
            is RsQualPathExpr -> true
            is RsLitExpr -> true
            is RsUnitExpr -> true

            is RsBinaryExpr -> null // Have to search if operation is overloaded
            is RsBlockExpr -> null  // Have to analyze lines, very hard case
            is RsCastExpr -> null;  // TODO: `expr.isPure()` maybe not true, think about side-effects, may panic while cast
            is RsCallExpr -> null   // All arguments and function itself must be pure, very hard case
            is RsForExpr -> null    // Always return (), if pure then can be replaced with it
            is RsIfExpr -> null
            is RsIndexExpr -> null  // Index trait can be overloaded, can panic if out of bounds
            is RsLambdaExpr -> null
            is RsLoopExpr -> null
            is RsMacroExpr -> null
            is RsMatchExpr -> null
            is RsMethodCallExpr -> null
            is RsRangeExpr -> null
            is RsUnaryExpr -> null  // May be overloaded
            is RsWhileExpr -> null
            else -> null
        }
    }

    /**
     * Enum class representing unary operator in rust.
     */
    enum class UnaryOperator {
        REF, // `&a`
        REF_MUT, // `&mut a`
        DEREF, // `*a`
        MINUS, // `-a`
        NOT, // `!a`
        BOX, // `box a`
    }

    /**
     * Operator of current psi node with unary operation.
     *
     * The result can be [REF] (`&`), [REF_MUT] (`&mut`),
     * [DEREF] (`*`), [MINUS] (`-`), [NOT] (`!`),
     * [BOX] (`box`) or `null` if none of these.
     */
    val RsUnaryExpr.operatorType: UnaryOperator?
        get() = when {
            this.and != null -> UnaryOperator.REF
            this.mut != null -> UnaryOperator.REF_MUT
            this.mul != null -> UnaryOperator.DEREF
            this.minus != null -> UnaryOperator.MINUS
            this.excl != null -> UnaryOperator.NOT
            this.box != null -> UnaryOperator.BOX
            else -> null
        }

}

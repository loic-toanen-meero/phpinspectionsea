package com.kalessil.phpStorm.phpInspectionsEA.inspectors.ifs;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.jetbrains.php.lang.inspections.PhpInspection;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.PhpPsiElementFactory;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.GenericPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.PhpLanguageUtil;
import org.jetbrains.annotations.NotNull;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) David Rodrigues <david.proweb@gmail.com>
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class InvertedIfElseConstructsInspector extends PhpInspection {
    private static final String message = "The if-else workflow is driven by inverted conditions, consider avoiding invertions.";

    @NotNull
    public String getShortName() {
        return "InvertedIfElseConstructsInspection";
    }

    @NotNull
    @Override
    public final PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder problemsHolder, final boolean isOnTheFly) {
        return new GenericPhpElementVisitor() {
            @Override
            public void visitPhpElse(@NotNull Else elseStatement) {
                if (this.shouldSkipAnalysis(elseStatement, StrictnessCategory.STRICTNESS_CATEGORY_CONTROL_FLOW)) { return; }

                final PsiElement elseBody = elseStatement.getStatement();
                if (elseBody instanceof GroupStatement) {
                    /* find if/elseif as main branch */
                    ControlStatement ifStatement = (ControlStatement) elseStatement.getParent();
                    final ElseIf[] elseIfs       = ((If) ifStatement).getElseIfBranches();
                    if (elseIfs.length > 0) {
                        ifStatement = elseIfs[elseIfs.length - 1];
                    }

                    final PsiElement ifBody    = ifStatement.getStatement();
                    final PsiElement condition = ifStatement.getCondition();
                    if (ifBody instanceof GroupStatement) {
                        if (condition instanceof UnaryExpression) {
                            final UnaryExpression unary = (UnaryExpression) condition;
                            if (OpenapiTypesUtil.is(unary.getOperation(), PhpTokenTypes.opNOT)) {
                                final PsiElement extractedCondition = ExpressionSemanticUtil.getExpressionTroughParenthesis(unary.getValue());
                                if (extractedCondition != null && !(extractedCondition instanceof PhpEmpty)) {
                                    final String newCondition = extractedCondition.getText();
                                    problemsHolder.registerProblem(
                                            elseStatement.getFirstChild(),
                                            message,
                                            new NormalizeWorkflowFix((GroupStatement) ifBody, (GroupStatement) elseBody, extractedCondition, newCondition)
                                    );
                                }
                            }
                        } else if (condition instanceof BinaryExpression) {
                            final BinaryExpression binary = (BinaryExpression) condition;
                            if (binary.getOperationType() == PhpTokenTypes.opIDENTICAL) {
                                /* extract condition */
                                final PsiElement left  = binary.getLeftOperand();
                                final PsiElement right = binary.getRightOperand();
                                if (left != null && right != null) {
                                    final PsiElement extractedCondition;
                                    if (PhpLanguageUtil.isFalse(left)) {
                                        extractedCondition = ExpressionSemanticUtil.getExpressionTroughParenthesis(right);
                                    } else if (PhpLanguageUtil.isFalse(right)) {
                                        extractedCondition = ExpressionSemanticUtil.getExpressionTroughParenthesis(left);
                                    } else  {
                                        extractedCondition = null;
                                    }
                                    /* if managed to extract condition, then proceed with reporting */
                                    if (extractedCondition != null) {
                                        final String newCondition = String.format("%s !== %s", left.getText(), right.getText());
                                        problemsHolder.registerProblem(
                                                elseStatement.getFirstChild(),
                                                message,
                                                new NormalizeWorkflowFix((GroupStatement) ifBody, (GroupStatement) elseBody, extractedCondition, newCondition)
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };
    }

    private static final class NormalizeWorkflowFix implements LocalQuickFix {
        private final SmartPsiElementPointer<GroupStatement> ifBody;
        private final SmartPsiElementPointer<GroupStatement> elseBody;
        private final SmartPsiElementPointer<PsiElement> condition;
        private final String newCondition;

        NormalizeWorkflowFix(@NotNull GroupStatement ifBody, @NotNull GroupStatement elseBody, @NotNull PsiElement condition, @NotNull String newCondition) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(condition.getProject());

            this.ifBody       = factory.createSmartPsiElementPointer(ifBody);
            this.elseBody     = factory.createSmartPsiElementPointer(elseBody);
            this.condition    = factory.createSmartPsiElementPointer(condition);
            this.newCondition = newCondition;
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return "Flip if-else to avoid not-operator";
        }

        @NotNull
        @Override
        public String getName() {
            return getFamilyName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor problemDescriptor) {
            final PsiElement ifBody    = this.ifBody.getElement();
            final PsiElement elseBody  = this.elseBody.getElement();
            final PsiElement condition = this.condition.getElement();
            if (ifBody != null && elseBody != null && condition != null && !project.isDisposed()) {
                PsiElement socket = condition;
                while (socket != null) {
                    final PsiElement parent = socket.getParent();
                    if (parent instanceof If || parent instanceof ElseIf) {
                        break;
                    }
                    socket = parent;
                }
                if (socket != null) {
                    final PsiElement newCondition = PhpPsiElementFactory
                            .createPhpPsiFromText(project, ParenthesizedExpression.class, '(' + this.newCondition + ')')
                            .getArgument();
                    if (newCondition != null) {
                        socket.replace(newCondition);

                        final PsiElement ifBodyCopy = ifBody.copy();
                        ifBody.replace(elseBody);
                        elseBody.replace(ifBodyCopy);
                    }
                }
            }
        }
    }
}

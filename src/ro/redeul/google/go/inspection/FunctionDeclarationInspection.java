package ro.redeul.google.go.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import ro.redeul.google.go.GoBundle;
import ro.redeul.google.go.inspection.fix.AddReturnStmtFix;
import ro.redeul.google.go.inspection.fix.RemoveFunctionResultFix;
import ro.redeul.google.go.lang.parser.GoElementTypes;
import ro.redeul.google.go.lang.psi.GoFile;
import ro.redeul.google.go.lang.psi.expressions.GoExpr;
import ro.redeul.google.go.lang.psi.expressions.GoPrimaryExpression;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteralFunction;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteralIdentifier;
import ro.redeul.google.go.lang.psi.expressions.primary.GoBuiltinCallExpression;
import ro.redeul.google.go.lang.psi.statements.GoBlockStatement;
import ro.redeul.google.go.lang.psi.statements.GoExpressionStatement;
import ro.redeul.google.go.lang.psi.statements.GoLabeledStatement;
import ro.redeul.google.go.lang.psi.statements.GoReturnStatement;
import ro.redeul.google.go.lang.psi.toplevel.GoFunctionDeclaration;
import ro.redeul.google.go.lang.psi.toplevel.GoFunctionParameter;
import ro.redeul.google.go.lang.psi.toplevel.GoMethodDeclaration;
import ro.redeul.google.go.lang.psi.visitors.GoRecursiveElementVisitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ro.redeul.google.go.inspection.InspectionUtil.*;
import static ro.redeul.google.go.lang.psi.utils.GoPsiUtils.getPrevSiblingIfItsWhiteSpaceOrComment;
import static ro.redeul.google.go.lang.psi.utils.GoPsiUtils.isNodeOfType;

public class FunctionDeclarationInspection
    extends AbstractWholeGoFileInspection
{
    @Override
    protected void doCheckFile(@NotNull GoFile file, @NotNull final InspectionResult result) {

        new GoRecursiveElementVisitor() {
            @Override
            public void visitFunctionDeclaration(GoFunctionDeclaration declaration) {
                checkFunction(result, declaration);
            }

            @Override
            public void visitMethodDeclaration(GoMethodDeclaration declaration) {
                checkFunction(result, declaration);
            }

            @Override
            public void visitFunctionLiteral(GoLiteralFunction literal) {
                checkFunction(result, literal);
            }
        }.visitFile(file);
    }

    public static void checkFunction(InspectionResult result, GoFunctionDeclaration function) {
        Context ctx = new Context(result, function);
        hasResultButNoReturnAtTheEnd(ctx);
        hasDuplicateArgument(ctx);
        hasRedeclaredParameterInResultList(ctx);
        hasReturnParameterCountMismatch(result, function);
        hasVariadicProblems(ctx);
    }

    private static void hasResultButNoReturnAtTheEnd(Context ctx) {
        if (hasResult(ctx) && hasBody(ctx) && !hasReturnAtTheEnd(ctx)) {
            LocalQuickFix fix1 = new AddReturnStmtFix(ctx.function);
            LocalQuickFix fix2 = new RemoveFunctionResultFix(ctx.function);
            PsiElement element = ctx.function.getBlock().getLastChild();
            ctx.result.addProblem(element, GoBundle.message("error.no.return.found"), fix1, fix2);
        }
    }

    private static void hasDuplicateArgument(Context ctx) {
        Set<String> parameters = new HashSet<String>();
        for (GoFunctionParameter fp : ctx.function.getParameters()) {
            for (GoLiteralIdentifier id : fp.getIdentifiers()) {
                if (id.isBlank()) {
                    continue;
                }

                String text = id.getText();
                if (parameters.contains(text)) {
                    ctx.result.addProblem(id, GoBundle.message("error.duplicate.argument", text));
                } else {
                    parameters.add(text);
                }
            }
        }
    }

    private static void hasRedeclaredParameterInResultList(Context ctx) {
        Set<String> parameters = new HashSet<String>(getParameterNames(ctx.function.getParameters()));

        for (GoFunctionParameter fp : ctx.function.getResults()) {
            for (GoLiteralIdentifier id : fp.getIdentifiers()) {
                String text = id.getText();
                if (!id.isBlank() && parameters.contains(text)) {
                    ctx.result.addProblem(id, GoBundle.message("error.redeclared.in.block", text));
                }
            }
        }
    }

    private static void hasReturnParameterCountMismatch(InspectionResult result, GoFunctionDeclaration function) {
        new ReturnVisitor(result, function).visitFunctionDeclaration(function);
    }

    private static void hasVariadicProblems(Context ctx) {
        // cannot use variadic in output argument list
        for (GoFunctionParameter parameter : ctx.function.getResults()) {
            if (parameter.isVariadic()) {
                ctx.result.addProblem(parameter, GoBundle.message("error.output.variadic"));
            }
        }

        GoFunctionParameter[] parameters = ctx.function.getParameters();
        if (parameters.length == 0) {
            return;
        }

        // only last argument could be variadic
        for (int i = 0; i < parameters.length - 1; i++) {
            GoFunctionParameter parameter = parameters[i];
            if (parameter.isVariadic()) {
                ctx.result.addProblem(parameter, GoBundle.message("error.variadic.not.the.last"));
            }
        }
    }

    private static boolean hasResult(Context ctx) {
        return ctx.function.getResults().length > 0;
    }

    private static boolean hasBody(Context ctx) {
        return ctx.function.getBlock() != null;
    }

    private static boolean hasReturnAtTheEnd(Context ctx) {
        GoBlockStatement block = ctx.function.getBlock();
        if (block == null) {
            return false;
        }

        PsiElement lastChild = getPrevSiblingIfItsWhiteSpaceOrComment(block.getLastChild());
        if (lastChild == null || !"}".equals(lastChild.getText())) {
            return false;
        }

        lastChild = getPrevSiblingIfItsWhiteSpaceOrComment(lastChild.getPrevSibling());
        while (lastChild instanceof GoLabeledStatement) {
            lastChild = ((GoLabeledStatement) lastChild).getStatement();
        }
        return isNodeOfType(lastChild, GoElementTypes.RETURN_STATEMENT) ||
               isPanicCall(lastChild);
    }

    private static boolean isPanicCall(PsiElement element) {
        if (!(element instanceof GoExpressionStatement)) {
            return false;
        }

        PsiElement call = element.getFirstChild();
        if (!(call instanceof GoBuiltinCallExpression)) {
            return false;
        }

        GoPrimaryExpression expression = ((GoBuiltinCallExpression) call).getBaseExpression();
        return expression != null && "panic".equals(expression.getText());
    }

    private static List<String> getParameterNames(GoFunctionParameter[] parameters) {
        List<String> parameterNames = new ArrayList<String>();
        for (GoFunctionParameter fp : parameters) {
            for (GoLiteralIdentifier id : fp.getIdentifiers()) {
                if (!id.isBlank()) {
                    parameterNames.add(id.getText());
                }
            }
        }
        return parameterNames;
    }

    /**
     * Recursively look for return statement, and compare its expression
     * list with function's result list
     */
    private static class ReturnVisitor extends GoRecursiveElementVisitor {
        private final InspectionResult result;
        int expectedResCount;
        boolean hasNamedReturns;

        public ReturnVisitor(InspectionResult result, GoFunctionDeclaration declaration) {
            this.result = result;
            this.expectedResCount = 0;

            hasNamedReturns = false;
            for (GoFunctionParameter resParam : declaration.getResults()) {
                expectedResCount += Math.max(resParam.getIdentifiers().length, 1);
                hasNamedReturns |= resParam.getIdentifiers().length > 0;
            }
        }

        @Override
        public void visitFunctionLiteral(GoLiteralFunction literal) {
            // stop the recursion here
        }

        @Override
        public void visitReturnStatement(GoReturnStatement statement) {
            GoExpr[] expressions = statement.getExpressions();
            int returnCount = expressions.length;
            if (returnCount == 1) {
                returnCount = getExpressionResultCount(expressions[0]);
            } else {
                checkExpressionShouldReturnOneResult(expressions, result);
            }

            // when a method specifies named return parameters it's ok to have
            // an empty return statement.
            if (returnCount == UNKNOWN_COUNT ||
                returnCount == 0 && hasNamedReturns ) {
                return;
            }

            if (expectedResCount < returnCount) {
                result.addProblem(statement, GoBundle.message("error.too.many.arguments.to.return"));
            } else if (expectedResCount > returnCount) {
                result.addProblem(statement, GoBundle.message("error.not.enough.arguments.to.return"));
            }
        }
    }

    private static class Context {
        public final InspectionResult result;
        public final GoFunctionDeclaration function;

        private Context(InspectionResult result, GoFunctionDeclaration function) {
            this.result = result;
            this.function = function;
        }
    }
}

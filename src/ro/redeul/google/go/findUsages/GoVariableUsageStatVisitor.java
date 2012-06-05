package ro.redeul.google.go.findUsages;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;
import ro.redeul.google.go.inspection.fix.RemoveVariableFix;
import ro.redeul.google.go.lang.parser.GoElementTypes;
import ro.redeul.google.go.lang.psi.GoFile;
import ro.redeul.google.go.lang.psi.GoPsiElement;
import ro.redeul.google.go.lang.psi.declarations.GoConstDeclaration;
import ro.redeul.google.go.lang.psi.declarations.GoConstDeclarations;
import ro.redeul.google.go.lang.psi.declarations.GoVarDeclaration;
import ro.redeul.google.go.lang.psi.declarations.GoVarDeclarations;
import ro.redeul.google.go.lang.psi.expressions.literals.GoIdentifier;
import ro.redeul.google.go.lang.psi.impl.GoPsiElementBase;
import ro.redeul.google.go.lang.psi.impl.expressions.literals.GoLiteralExprImpl;
import ro.redeul.google.go.lang.psi.toplevel.GoFunctionDeclaration;
import ro.redeul.google.go.lang.psi.toplevel.GoFunctionParameter;
import ro.redeul.google.go.lang.psi.toplevel.GoFunctionParameterList;
import ro.redeul.google.go.lang.psi.visitors.GoRecursiveElementVisitor2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoVariableUsageStatVisitor extends GoRecursiveElementVisitor2 {
    private List<ProblemDescriptor> problems = new ArrayList<ProblemDescriptor>();
    private InspectionManager manager;
    private Context ctx;

    public GoVariableUsageStatVisitor(InspectionManager manager) {
        this.manager = manager;
    }

    public ProblemDescriptor[] getProblems() {
        return problems.toArray(new ProblemDescriptor[problems.size()]);
    }

    private void beforeVisitFile(GoFile file) {
        ctx = new Context(problems, manager, getGlobalVariables(file));
    }

    private void afterVisitFile(GoFile file) {
        for (VariableUsage v : ctx.popLastScopeLevel().values()) {
            if (!v.isUsed()) {
                ctx.unusedGlobalVariable(v);
            }
        }
    }

    @Override
    protected void beforeVisitElement(PsiElement element) {
        if (element instanceof GoFile) {
            beforeVisitFile((GoFile) element);
        } else if (element instanceof GoVarDeclarations) {
            for (GoVarDeclaration gvd : ((GoVarDeclarations) element).getDeclarations()) {
                beforeVisitGoVarDeclaration(gvd);
            }
        } else if (element instanceof GoVarDeclaration) {
            beforeVisitGoVarDeclaration((GoVarDeclaration) element);
        } else if (element instanceof GoConstDeclarations) {
            for (GoConstDeclaration gcd : ((GoConstDeclarations) element).getDeclarations()) {
                beforeVisitGoConstDeclaration(gcd);
            }
        } else if (element instanceof GoConstDeclaration) {
            beforeVisitGoConstDeclaration((GoConstDeclaration) element);
        } else if (element instanceof GoIdentifier) {
            beforeVisitIdentifier((GoIdentifier) element);
        } else if (element instanceof GoFunctionDeclaration) {
            beforeVisitFunctionDeclaration((GoFunctionDeclaration) element);
        } else if (couldOpenNewScope(element)) {
            ctx.addNewScopeLevel();
        }
    }

    private static boolean couldOpenNewScope(PsiElement element) {
        if (!(element instanceof GoPsiElementBase)) {
            return false;
        }

        GoPsiElementBase gpeb = (GoPsiElementBase) element;
        IElementType tt = gpeb.getTokenType();
        return tt == GoElementTypes.IF_STATEMENT || tt == GoElementTypes.FOR_STATEMENT || tt == GoElementTypes.BLOCK_STATEMENT;
    }

    @Override
    protected void afterVisitElement(PsiElement element) {
        if (element instanceof GoFile) {
            afterVisitFile((GoFile) element);
        } else if (element instanceof GoFunctionDeclaration) {
            afterVisitGoFunctionDeclaration((GoFunctionDeclaration) element);
        } else if (couldOpenNewScope(element)) {
            for (VariableUsage v : ctx.popLastScopeLevel().values()) {
                if (!v.isUsed()) {
                    ctx.unusedVariable(v);
                }
            }
        }
    }

    private void beforeVisitGoConstDeclaration(GoConstDeclaration gcd) {
        for (GoIdentifier id : gcd.getIdentifiers()) {
            ctx.addDefinition(id);
        }
    }

    private void beforeVisitGoVarDeclaration(GoVarDeclaration gvd) {
        for (GoIdentifier id : gvd.getIdentifiers()) {
            ctx.addDefinition(id);
        }
    }

    public void beforeVisitIdentifier(GoIdentifier id) {
        if (isFunctionOrMethodCall(id)) {
            return;
        }
        ctx.addUsage(id);
    }

    private boolean isFunctionOrMethodCall(GoIdentifier id) {
        if (!(id.getParent() instanceof GoLiteralExprImpl)) {
            return false;
        }

        PsiElement grandpa = id.getParent().getParent();
        return grandpa.getNode().getElementType() == GoElementTypes.CALL_OR_CONVERSION_EXPRESSION &&
                grandpa.getFirstChild().isEquivalentTo(id.getParent());
    }

    public void beforeVisitFunctionDeclaration(GoFunctionDeclaration fd) {
        getFunctionParameters(fd);
    }

    public void afterVisitGoFunctionDeclaration(GoFunctionDeclaration fd) {
        for (VariableUsage v : ctx.popLastScopeLevel().values()) {
            if (!v.isUsed()) {
                ctx.unusedParameter(v);
            }
        }
    }
    private Map<String, VariableUsage> getFunctionParameters(GoFunctionDeclaration fd) {
        Map<String, VariableUsage> variables = ctx.addNewScopeLevel();
        GoFunctionParameterList parameters = fd.getParameters();
        if (parameters == null) {
            return variables;
        }

        GoFunctionParameter[] functionParameters = parameters.getFunctionParameters();
        if (functionParameters == null) {
            return variables;
        }

        for (GoFunctionParameter fp : functionParameters) {
            for (GoIdentifier id : fp.getIdentifiers()) {
                variables.put(id.getName(), new VariableUsage(id));
            }
        }
        return variables;
    }

    private Map<String, VariableUsage> getGlobalVariables(GoFile file) {
        Map<String, VariableUsage> variables = new HashMap<String, VariableUsage>();
        for (GoConstDeclarations allConsts : file.getConsts()) {
            for (GoConstDeclaration cd : allConsts.getDeclarations()) {
                for (GoIdentifier id : cd.getIdentifiers()) {
                    variables.put(id.getName(), new VariableUsage(id));
                }
            }
        }

        for (GoVarDeclarations allVariables : file.getGlobalVariables()) {
            for (GoVarDeclaration vd : allVariables.getDeclarations()) {
                for (GoIdentifier id : vd.getIdentifiers()) {
                    variables.put(id.getName(), new VariableUsage(id));
                }
            }
        }
        return variables;
    }

    private static class Context {
        public final List<ProblemDescriptor> problems;
        public final InspectionManager manager;
        public final List<Map<String, VariableUsage>> variables = new ArrayList<Map<String, VariableUsage>>();

        Context(List<ProblemDescriptor> problems, InspectionManager manager, Map<String, VariableUsage> global) {
            this.problems = problems;
            this.manager = manager;
            this.variables.add(global);
        }

        public Map<String, VariableUsage> addNewScopeLevel() {
            Map<String, VariableUsage> variables = new HashMap<String, VariableUsage>();
            this.variables.add(variables);
            return variables;
        }

        public Map<String, VariableUsage> popLastScopeLevel() {
            return variables.remove(variables.size() - 1);
        }

        public void unusedVariable(VariableUsage variableUsage) {
            if (variableUsage.isBlank()) {
                return;
            }

            addProblem(variableUsage, "Unused variable", ProblemHighlightType.ERROR, new RemoveVariableFix());
        }

        public void unusedParameter(VariableUsage variableUsage) {
            if (!variableUsage.isBlank()) {
                addProblem(variableUsage, "Unused parameter", ProblemHighlightType.LIKE_UNUSED_SYMBOL, null);
            }
        }

        public void unusedGlobalVariable(VariableUsage variableUsage) {
            addProblem(variableUsage, "Unused global", ProblemHighlightType.LIKE_UNUSED_SYMBOL, new RemoveVariableFix());
        }

        public void undefinedVariable(VariableUsage variableUsage) {
            addProblem(variableUsage, "Undefined variable", ProblemHighlightType.ERROR, null);
        }

        private void addProblem(VariableUsage variableUsage, String desc, ProblemHighlightType highlightType,
                                @Nullable LocalQuickFix fix) {
            problems.add(manager.createProblemDescriptor(variableUsage.element, desc, fix, highlightType, true));
        }

        public void addDefinition(GoPsiElement element) {
            variables.get(variables.size() - 1).put(element.getText(), new VariableUsage(element));
        }

        public void addUsage(GoPsiElement element) {
            for (int i = variables.size() - 1; i >= 0; i--) {
                VariableUsage variableUsage = variables.get(i).get(element.getText());
                if (variableUsage != null) {
                    variableUsage.addUsage(element);
                    return;
                }
            }

            undefinedVariable(new VariableUsage(element));
        }
    }
}
////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
// Copyright 2026 trustytrojan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Prominic.NET, Inc.
// Author: trustytrojan
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls.compiler.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;
import net.prominic.lsp.utils.Ranges;

public class GroovyASTUtils {
    public static ASTNode getEnclosingNodeOfType(ASTNode offsetNode, Class<? extends ASTNode> nodeType,
            ASTNodeVisitor astVisitor) {
        ASTNode current = offsetNode;
        while (current != null) {
            if (nodeType.isInstance(current)) {
                return current;
            }
            current = astVisitor.getParent(current);
        }
        return null;
    }

    public static boolean isCallableObjectCall(final MethodCallExpression mce, final ASTNodeVisitor ast,
            final FileContentsTracker fct) {
        if (ast == null || fct == null)
            return false;
        final var typeOfNode = getTypeOfNode(mce.getObjectExpression(), ast);
        final var hasCallMethod = (typeOfNode != null) && typeOfNode.hasPossibleMethod("call", mce.getArguments());
        final var callRange = GroovyLanguageServerUtils.astNodeToRange(mce);
        final var uri = ast.getURI(mce);
        if (uri == null)
            return false;
        final var text = fct.getContents(uri);
        if (text == null)
            return false;
        final var notExplicitCallMethodCall = (callRange != null)
                && !Ranges.getSubstring(text, callRange).matches(".*\\.\\s*call\\s*\\(.*");
        return hasCallMethod && notExplicitCallMethodCall;
    }

    private static ASTNode getDefinitionOfMethodCall(final MethodCallExpression mce, final boolean strict,
            final ASTNodeVisitor ast,
            final FileContentsTracker fct) {
        final MethodNode definition;

        // Groovy's STC fills in the DIRECT_METHOD_CALL_TARGET metadata when it finds a
        // matching method.
        // Use pattern matching here because we need to return an ASTNode.
        // It is expected that the STC fills in DIRECT_METHOD_CALL_TARGET with a
        // MethodNode, so we pattern match for it.
        if (mce.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET) instanceof final MethodNode mn)
            definition = mn;
        else if (mce.getMethodTarget() != null)
            definition = mce.getMethodTarget();
        else
            definition = GroovyASTUtils.getMethodFromCallExpression(mce, ast);

        return getDefinition(definition, strict, ast);
    }

    public static ASTNode getDefinition(final ASTNode node, final boolean strict, final ASTNodeVisitor astVisitor) {
        return getDefinition(node, strict, astVisitor, null);
    }

    public static ASTNode getDefinition(ASTNode node, final boolean strict, final ASTNodeVisitor ast,
            final FileContentsTracker fct) {
        if (node == null) {
            return null;
        }

        final var parentNode = ast.getParent(node);

        if (node instanceof final ExpressionStatement es) {
            node = es.getExpression();
        }

        if (node instanceof final ClassNode cn) {
            return tryToResolveOriginalClassNode(cn, strict, ast);
        } else if (node instanceof final ConstructorCallExpression cce) {
            final var methodNode = GroovyASTUtils.getMethodFromCallExpression(cce, ast);
            if (methodNode == null) {
                // The class has no explicit constructor, so return the ClassNode itself
                return tryToResolveOriginalClassNode(cce.getType(), strict, ast);
            }
            return methodNode;
        } else if (node instanceof final DeclarationExpression de) {
            if (!de.isMultipleAssignmentDeclaration()) {
                final var variableExpression = de.getVariableExpression();

                if (variableExpression.isDynamicTyped()) {
                    // We run the STC over the AST now, so prefer the inferred type if available.
                    var inferredType = variableExpression.<ClassNode>getNodeMetaData("groovyls-original-inferred-type");
                    if (inferredType != null)
                        return inferredType;

                    inferredType = variableExpression.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
                    if (inferredType != null)
                        return inferredType;

                    // Otherwise fallback to the type of the initializing expression.
                    return tryToResolveOriginalClassNode(de.getRightExpression().getType(), strict, ast);
                } else {
                    final var originType = variableExpression.getOriginType();
                    return tryToResolveOriginalClassNode(originType, strict, ast);
                }
            }
        } else if (node instanceof final ClassExpression ce) {
            return tryToResolveOriginalClassNode(ce.getType(), strict, ast);
        } else if (node instanceof final ImportNode in) {
            return tryToResolveOriginalClassNode(in.getType(), strict, ast);
        } else if (node instanceof final MethodNode mn) {
            if (mn.isSynthetic()) {
                final var params1 = mn.getParameters();

                nextMethod: for (final var method : mn.getDeclaringClass().getMethods(mn.getName())) {
                    if (!method.hasDefaultValue())
                        continue;

                    final var params2 = method.getParameters();
                    if (params1.length >= params2.length)
                        continue;

                    // 1. Count mandatory parameters in the candidate method
                    var mandatoryCount = 0;
                    for (final var p : params2) {
                        if (!p.hasInitialExpression()) {
                            mandatoryCount++;
                        }
                    }

                    // An overload must contain at least all mandatory parameters
                    if (params1.length < mandatoryCount)
                        continue;

                    // 2. Groovy keeps all mandatory params + the first N optional params
                    // (left-to-right)
                    final var optToKeep = params1.length - mandatoryCount;
                    int optSeen = 0, idx1 = 0;

                    for (final var p2 : params2) {
                        if (p2.hasInitialExpression()) {
                            if (optSeen < optToKeep) {
                                optSeen++;
                            } else {
                                // Skip optional parameters beyond the budget for this overload
                                continue;
                            }
                        }

                        // Compare expected parameter type with synthetic method parameter type
                        if (idx1 >= params1.length || !params1[idx1].getType().equals(p2.getType())) {
                            continue nextMethod;
                        }
                        idx1++;
                    }

                    if (idx1 == params1.length) {
                        return method;
                    }
                }
            }
            return mn;
        } else if (node instanceof ArgumentListExpression && parentNode instanceof final MethodCallExpression mce) {
            // In cpptools and clangd, hovering over the parens of a call of a callable
            // object will show the actual method being called. There's no need to restrict
            // this behavior to callable objects, though.
            return getDefinitionOfMethodCall(mce, strict, ast, fct);
        } else if (node instanceof ConstantExpression) {
            if (parentNode instanceof final MethodCallExpression mce) {
                if (isCallableObjectCall(mce, ast, fct))
                    return getDefinition(mce.getObjectExpression(), strict, ast);

                return getDefinitionOfMethodCall(mce, strict, ast, fct);
            } else if (parentNode instanceof final PropertyExpression pe) {
                // Groovy's STC fills in the DIRECT_METHOD_CALL_TARGET metadata for
                // PropertyExpressions where a matching getter is available.
                // For example: `new Object().class` calls `Object#getClass()`.
                // Use pattern matching here because we need to return an ASTNode.
                // It is expected that the STC fills in DIRECT_METHOD_CALL_TARGET with a
                // MethodNode, so we pattern match for it.
                if (pe.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET) instanceof final MethodNode mn)
                    return mn;

                final var propNode = GroovyASTUtils.getPropertyFromExpression(pe, ast);
                if (propNode != null)
                    return propNode;

                return GroovyASTUtils.getFieldFromExpression(pe, ast);
            }
        } else if (node instanceof final VariableExpression ve) {
            final var accessedVariable = ve.getAccessedVariable();
            if (accessedVariable instanceof final ASTNode an) {
                // System.err.printf("getDefinition: ve=%s accessedVariable=%s\n", ve,
                // accessedVariable);
                return an;
            }
            // DynamicVariable is not an ASTNode, so skip it
            return null;
        } else if (node instanceof Variable) {
            return node;
        }

        return null;
    }

    public static ASTNode getPropertyOrMethodCallFromConstantExpression(
            final ConstantExpression ce,
            final ASTNodeVisitor astVisitor) {
        final var parentNode = astVisitor.getParent(ce);
        if (parentNode instanceof PropertyExpression || parentNode instanceof MethodCallExpression)
            return parentNode;
        return null;
    }

    public static ASTNode getTypeDefinition(final ASTNode node, final ASTNodeVisitor astVisitor) {
        final var definitionNode = getDefinition(node, false, astVisitor);
        if (definitionNode == null) {
            return null;
        }
        if (definitionNode instanceof final MethodNode mn) {
            return tryToResolveOriginalClassNode(mn.getReturnType(), true, astVisitor);
        } else if (definitionNode instanceof final Variable v) {
            return tryToResolveOriginalClassNode(v.getOriginType(), true, astVisitor);
        }
        return null;
    }

    public static List<ASTNode> getReferences(ASTNode node, ASTNodeVisitor ast) {
        ASTNode definitionNode = getDefinition(node, true, ast);
        if (definitionNode == null) {
            return Collections.emptyList();
        }
        return ast.getNodes().stream().filter(otherNode -> {
            ASTNode otherDefinition = getDefinition(otherNode, false, ast);
            return definitionNode.equals(otherDefinition) && node.getLineNumber() != -1 && node.getColumnNumber() != -1;
        }).collect(Collectors.toList());
    }

    private static ClassNode tryToResolveOriginalClassNode(ClassNode node, boolean strict, ASTNodeVisitor ast) {
        for (ClassNode originalNode : ast.getClassNodes()) {
            if (originalNode.equals(node)) {
                return originalNode;
            }
        }
        if (strict) {
            return null;
        }
        return node;
    }

    public static PropertyNode getPropertyFromExpression(PropertyExpression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node.getObjectExpression(), astVisitor);
        if (classNode != null) {
            return classNode.getProperty(node.getProperty().getText());
        }
        return null;
    }

    public static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node.getObjectExpression(), astVisitor);
        if (classNode != null) {
            FieldNode fn = classNode.getField(node.getProperty().getText());
            if (fn != null && memberIsVisible(fn, node, astVisitor)) {
                return fn;
            }
        }
        return null;
    }

    public static List<FieldNode> getFieldsForLeftSideOfPropertyExpression(Expression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node, astVisitor);
        if (classNode != null) {
            List<ClassNode> classNodes = new ArrayList<>();
            classNodes.add(classNode);

            boolean statics = node instanceof ClassExpression;

            List<FieldNode> result = new ArrayList<>();
            int i = 0;
            while (i < classNodes.size()) {
                ClassNode current = classNodes.get(i);

                result.addAll(current.getFields().stream().filter(fieldNode -> {
                    return fieldNode.isPublic() && (statics ? fieldNode.isStatic() : !fieldNode.isStatic());
                }).collect(Collectors.toList()));

                visitAllSupertypes(current, classNodes);
                i++;
            }
            return result;
        }
        return Collections.emptyList();
    }

    public static List<PropertyNode> getPropertiesForLeftSideOfPropertyExpression(Expression node,
            ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node, astVisitor);
        if (classNode != null) {
            List<ClassNode> classNodes = new ArrayList<>();
            classNodes.add(classNode);

            boolean statics = node instanceof ClassExpression;

            List<PropertyNode> result = new ArrayList<>();
            int i = 0;
            while (i < classNodes.size()) {
                ClassNode current = classNodes.get(i);

                result.addAll(current.getProperties().stream().filter(propNode -> {
                    return propNode.isPublic() && (statics ? propNode.isStatic() : !propNode.isStatic());
                }).collect(Collectors.toList()));

                visitAllSupertypes(current, classNodes);
                i++;
            }
            return result;
        }
        return Collections.emptyList();
    }

    public static List<MethodNode> getMethodsForLeftSideOfPropertyExpression(Expression node,
            ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node, astVisitor);
        if (classNode != null) {
            List<ClassNode> classNodes = new ArrayList<>();
            classNodes.add(classNode);

            boolean statics = node instanceof ClassExpression;

            List<MethodNode> result = new ArrayList<>();
            int i = 0;
            while (i < classNodes.size()) {
                ClassNode current = classNodes.get(i);

                result.addAll(current.getMethods().stream().filter(methodNode -> {
                    return methodNode.isPublic() && (statics ? methodNode.isStatic() : !methodNode.isStatic());
                }).collect(Collectors.toList()));

                visitAllSupertypes(current, classNodes);
                i++;
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static void visitAllSupertypes(ClassNode current, List<ClassNode> classNodes) {
        for (ClassNode interfaceNode : current.getInterfaces()) {
            classNodes.add(interfaceNode);
        }
        ClassNode superClassNode = null;
        try {
            superClassNode = current.getSuperClass();
        } catch (NoClassDefFoundError e) {
            // this is fine, we'll just treat it as null
        }
        if (superClassNode != null) {
            classNodes.add(superClassNode);
        }
    }

    public static ClassNode getTypeOfNode(ASTNode node, ASTNodeVisitor astVisitor) {
        if (node instanceof BinaryExpression) {
            BinaryExpression binaryExpr = (BinaryExpression) node;
            Expression leftExpr = binaryExpr.getLeftExpression();
            if (binaryExpr.getOperation().getText().equals("[") && leftExpr.getType().isArray()) {
                return leftExpr.getType().getComponentType();
            }
        } else if (node instanceof ClassExpression) {
            ClassExpression expression = (ClassExpression) node;
            // This means it's an expression like this: SomeClass.someProp
            return expression.getType();
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression expression = (ConstructorCallExpression) node;
            return expression.getType();
        } else if (node instanceof final MethodCallExpression mce) {
            // Groovy's STC fills in INFERRED_TYPE with the return type of the method it
            // stored in DIRECT_METHOD_CALL_TARGET.
            // It is expected that the STC fills in INFERRED_TYPE with a ClassNode, so we
            // pattern match for it.
            if (mce.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE) instanceof final ClassNode cn)
                return cn;

            final var method = GroovyASTUtils.getMethodFromCallExpression(mce, astVisitor);
            if (method != null)
                return method.getReturnType();

            return mce.getType();
        } else if (node instanceof final PropertyExpression pe) {
            // Groovy's STC fills in INFERRED_TYPE with the return type of the method it
            // stored in DIRECT_METHOD_CALL_TARGET.
            // It is expected that the STC fills in INFERRED_TYPE with a ClassNode, so we
            // pattern match for it.
            if (pe.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE) instanceof final ClassNode cn)
                return cn;

            final var propNode = GroovyASTUtils.getPropertyFromExpression(pe, astVisitor);
            if (propNode != null)
                return getTypeOfNode(propNode, astVisitor);

            final var fieldNode = GroovyASTUtils.getFieldFromExpression(pe, astVisitor);
            if (fieldNode != null)
                return getTypeOfNode(fieldNode, astVisitor);

            return pe.getType();
        } else if (node instanceof final Variable var) {
            if (var.getName().equals("this")) {
                ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(node, ClassNode.class, astVisitor);
                if (enclosingClass != null) {
                    return enclosingClass;
                }
            } else if (var.isDynamicTyped()) {
                if (var instanceof final VariableExpression ve) {
                    // We run the STC over the AST now, so prefer the inferred type if available.
                    // Use pattern matching because we need to return a ClassNode.
                    // It is expected that the STC fills in INFERRED_TYPE with a ClassNode, so we
                    // pattern match for it.
                    if (ve.getNodeMetaData("groovyls-original-inferred-type") instanceof final ClassNode cn)
                        return cn;

                    if (ve.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE) instanceof final ClassNode cn)
                        return cn;
                }

                ASTNode defNode = GroovyASTUtils.getDefinition(node, false, astVisitor);
                if (defNode instanceof Variable) {
                    Variable defVar = (Variable) defNode;
                    if (defVar.hasInitialExpression()) {
                        return getTypeOfNode(defVar.getInitialExpression(), astVisitor);
                    } else {
                        ASTNode declNode = astVisitor.getParent(defNode);
                        if (declNode instanceof DeclarationExpression) {
                            DeclarationExpression decl = (DeclarationExpression) declNode;
                            return getTypeOfNode(decl.getRightExpression(), astVisitor);
                        }
                    }
                } else {
                    // gdsl: Lookup the variable's text as a field of the enclosing script class.
                    ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(node, ClassNode.class, astVisitor);
                    if (enclosingClass != null && enclosingClass.isScript()) {
                        FieldNode fn = enclosingClass.getField(node.getText());
                        if (fn != null)
                            return fn.getType();
                    }
                }
            }
            if (var.getOriginType() != null) {
                return var.getOriginType();
            }
        }
        if (node instanceof Expression) {
            Expression expression = (Expression) node;
            return expression.getType();
        }
        return null;
    }

    private static boolean memberIsVisible(MethodNode member, Expression expr, ASTNodeVisitor astVisitor) {
        if (member == null) {
            return true;
        }
        ClassNode declaringClass = member.getDeclaringClass();
        ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(expr, ClassNode.class, astVisitor);
        if (enclosingClass == null) {
            // Not sure what's going on here, just return.
            return true;
        }
        if (enclosingClass.equals(declaringClass)) {
            // We are in the same class as the object we are getting members from.
            return true;
        }
        if (enclosingClass.isDerivedFrom(declaringClass)) {
            // We are in the body of class B which extends class A,
            // and we are accessing a member of class A.
            // Only return if protected or public.
            if (member.isProtected() || member.isPublic())
                return true;
        }
        // All other cases: only return if public.
        return member.isPublic();
    }

    private static boolean memberIsVisible(FieldNode member, Expression expr, ASTNodeVisitor astVisitor) {
        if (member == null) {
            return true;
        }
        ClassNode declaringClass = member.getDeclaringClass();
        ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(expr, ClassNode.class, astVisitor);
        if (enclosingClass == null) {
            // Not sure what's going on here, just return.
            return true;
        }
        if (enclosingClass.equals(declaringClass)) {
            // We are in the same class as the object we are getting members from.
            return true;
        }
        if (enclosingClass.isDerivedFrom(declaringClass)) {
            // We are in the body of class B which extends class A,
            // and we are accessing a member of class A.
            // Only return if protected or public.
            if (member.isProtected() || member.isPublic())
                return true;
        }
        // All other cases: only return if public.
        return member.isPublic();
    }

    public static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor) {
        if (node instanceof MethodCallExpression) {
            MethodCallExpression methodCallExpr = (MethodCallExpression) node;
            ClassNode leftType = getTypeOfNode(methodCallExpr.getObjectExpression(), astVisitor);
            if (leftType != null) {
                return leftType.getAllDeclaredMethods().stream()
                        .filter(m -> m.getName().equals(methodCallExpr.getMethod().getText())
                                && memberIsVisible(m, methodCallExpr, astVisitor))
                        .collect(Collectors.toList());
            }
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression constructorCallExpr = (ConstructorCallExpression) node;
            ClassNode constructorType = constructorCallExpr.getType();
            if (constructorType != null) {
                return constructorType.getDeclaredConstructors().stream().map(constructor -> (MethodNode) constructor)
                        .filter(mn -> memberIsVisible(mn, constructorCallExpr, astVisitor))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor) {
        return getMethodFromCallExpression(node, astVisitor, -1);
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor, int argIndex) {
        List<MethodNode> possibleMethods = getMethodOverloadsFromCallExpression(node, astVisitor);
        if (!possibleMethods.isEmpty() && node.getArguments() instanceof ArgumentListExpression) {
            ArgumentListExpression actualArguments = (ArgumentListExpression) node.getArguments();
            MethodNode foundMethod = possibleMethods.stream().max(new Comparator<MethodNode>() {
                public int compare(MethodNode m1, MethodNode m2) {
                    Parameter[] p1 = m1.getParameters();
                    Parameter[] p2 = m2.getParameters();
                    int m1Value = calculateArgumentsScore(p1, actualArguments, argIndex);
                    int m2Value = calculateArgumentsScore(p2, actualArguments, argIndex);
                    if (m1Value > m2Value) {
                        return 1;
                    } else if (m1Value < m2Value) {
                        return -1;
                    }
                    return 0;
                }
            }).orElse(null);
            return foundMethod;
        }
        return null;
    }

    private static int calculateArgumentsScore(Parameter[] parameters, ArgumentListExpression arguments, int argIndex) {
        int score = 0;
        int paramCount = parameters.length;
        int expressionsCount = arguments.getExpressions().size();
        int argsCount = expressionsCount;
        if (argIndex >= argsCount) {
            argsCount = argIndex + 1;
        }
        int minCount = Math.min(paramCount, argsCount);
        if (minCount == 0 && paramCount == argsCount) {
            score++;
        }
        for (int i = 0; i < minCount; i++) {
            ClassNode argType = (i < expressionsCount) ? arguments.getExpression(i).getType() : null;
            ClassNode paramType = (i < paramCount) ? parameters[i].getType() : null;
            if (argType != null && paramType != null) {
                if (argType.equals(paramType)) {
                    // equal types are preferred
                    score += 1000;
                } else if (argType.isDerivedFrom(paramType)) {
                    // subtypes are nice, but less important
                    score += 100;
                } else {
                    // if a type doesn't match at all, it's not worth much
                    score++;
                }
            } else if (paramType != null) {
                // extra parameters are like a type not matching
                score++;
            }
        }
        return score;
    }

    public static Range findAddImportRange(ASTNode offsetNode, ASTNodeVisitor astVisitor) {
        ModuleNode moduleNode = (ModuleNode) GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ModuleNode.class,
                astVisitor);
        if (moduleNode == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        ASTNode afterNode = null;
        if (afterNode == null) {
            List<ImportNode> importNodes = moduleNode.getImports();
            if (importNodes.size() > 0) {
                afterNode = importNodes.get(importNodes.size() - 1);
            }
        }
        if (afterNode == null) {
            afterNode = moduleNode.getPackage();
        }
        if (afterNode == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        Range nodeRange = GroovyLanguageServerUtils.astNodeToRange(afterNode);
        if (nodeRange == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        Position position = new Position(nodeRange.getEnd().getLine() + 1, 0);
        return new Range(position, position);
    }

    public static void debugPrint(final ASTNode expr, final FileContentsTracker fct, final ASTNodeVisitor ast) {
        System.err.printf("debugPrint: %s\n  text: '%s'\n", expr, expr.getText());

        if (expr.getNodeMetaData("groovyls-original-inferred-type") instanceof final ClassNode cn) {
            System.err.printf("  original_inferred_type: %s\n", cn);
        }

        if (expr.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE) instanceof final ClassNode cn) {
            System.err.printf("  inferred_type: %s\n", cn);
        }

        if (expr.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE) instanceof final ClassNode cn) {
            System.err.printf("  inferred_return_type: %s\n", cn);
        }

        if (expr.getNodeMetaData(StaticTypesMarker.DECLARATION_INFERRED_TYPE) instanceof final ClassNode cn) {
            System.err.printf("  declaration_inferred_type: %s\n", cn);
        }

        if (expr.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET) instanceof final MethodNode mn) {
            System.err.printf("  direct_method_call_target: %s\n", mn);
        }

        if (expr.getNodeMetaData(StaticTypesMarker.READONLY_PROPERTY) instanceof final Boolean b) {
            System.err.printf("  readonly_property: %s\n", b);
        }

        if (expr instanceof final AnnotatedNode an) {
            System.err.printf("  is_synthetic: %s\n", an.isSynthetic());
        }

        if (expr instanceof final Expression e) {
            System.err.printf("  type: %s\n", e.getType());
        }

        if (expr instanceof final MethodCallExpression mce) {
            System.err.printf("  method_target: %s\n", mce.getMethodTarget());
        }

        if (expr instanceof final MethodNode mn) {
            System.err.printf("  return_type: %s\n", mn.getReturnType());
        }

        final var range = GroovyLanguageServerUtils.astNodeToRange(expr);
        if (range != null) {
            // Range#toString() prints newlines, let's print it ourselves
            final var start = range.getStart();
            final var end = range.getEnd();
            System.err.printf("  range: (%s, %s), (%s, %s)\n",
                    start.getLine(), start.getCharacter(),
                    end.getLine(), end.getCharacter());
        }

        if (ast != null) {
            final var uri = ast.getURI(expr);
            if (uri != null) {
                System.err.printf("  uri: '%s'\n", uri);
                if (range != null && fct != null) {
                    final var contents = fct.getContents(uri);
                    if (contents != null)
                        System.err.printf("  range_to_text: '%s'\n", Ranges.getSubstring(contents, range));
                }
            }

            if (expr instanceof ConstantExpression) {
                final var parent = ast.getParent(expr);
                if (parent instanceof MethodCallExpression || parent instanceof PropertyExpression) {
                    System.err.print("parent of ConstantExpression: ");
                    debugPrint(parent, fct, ast);
                }
            }
        }

        if (expr instanceof final Variable v && v.hasInitialExpression()) {
            System.err.print("this Variable: ");
            debugPrintVariable(v, fct, ast);
            if (v.hasInitialExpression()) {
                System.err.print("initial expression of Variable: ");
                debugPrint(v.getInitialExpression(), fct, ast);
            }
        }

        if (expr instanceof final VariableExpression ve && ve.getAccessedVariable() != null) {
            System.err.print("accessed variable: ");
            debugPrintVariable(ve.getAccessedVariable(), fct, ast);
        }
    }

    public static void debugPrintVariable(final Variable v, final FileContentsTracker fct, final ASTNodeVisitor ast) {
        System.err.printf("debugPrintVariable: %s\n  type: %s\n  name: '%s'\n", v, v.getType(), v.getName());

        System.err.printf("  final: %s\n  dynamic_typed: %s\n",
                v.isFinal(),
                v.isDynamicTyped());
    
        if (v.hasInitialExpression()) {
            System.err.printf("initial expression of Variable: ", v);
            debugPrint(v.getInitialExpression(), fct, ast);
        }
    }
}
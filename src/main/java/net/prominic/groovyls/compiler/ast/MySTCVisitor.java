package net.prominic.groovyls.compiler.ast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.codehaus.groovy.transform.stc.TypeCheckingContext;

public class MySTCVisitor extends StaticTypeCheckingVisitor {
	public MySTCVisitor(SourceUnit source, ClassNode classNode) {
		super(source, classNode);
	}

	private boolean poppingAssignmentTracking;

	@Override
	protected Map<VariableExpression, ClassNode> popAssignmentTracking(
			Map<VariableExpression, List<ClassNode>> oldTracker) {
		// final var tracker = getIfElseForWhileAssignmentTracker();
		// for (final var entry : tracker.entrySet()) {
		// 	final var types = entry.getValue();
		// 	System.out.printf("MySTCVisitor.popAssignmentTracking: var=%s types=%s lub=%s\n", entry.getKey(), types,
		// 			WideningCategories.lowestUpperBound(types));
		// }
		poppingAssignmentTracking = true;
		final var result = super.popAssignmentTracking(oldTracker);
		poppingAssignmentTracking = false;
		// System.out.println("MySTCVisitor.popAssignmentTracking: returning");
		return result;
	}

	@Override
	protected void storeType(final Expression exp, ClassNode cn) {
		if (!(exp instanceof final VariableExpression ve)) {
			super.storeType(exp, cn);
			return;
		}

		if (poppingAssignmentTracking && ve.getNodeMetaData("groovyls-during-popAssignmentTracking") == null) {
			// System.out.println(
			// 		"MySTCVisitor.storeType: poppingAssignmentTracking is true, storing this in the metadata of " + ve);
			ve.setNodeMetaData("groovyls-during-popAssignmentTracking", true);
		}

		// System.out.printf("MySTCVisitor.storeType: ve=%s cn=%s\n", ve, cn);

		if (ve.getAccessedVariable() == ve) {
			final var existingInferredType = getInferredType(ve);
			if (existingInferredType != null) {
				// This VariableExpression is the **original** object created by a
				// DeclarationExpression, and it ALREADY has an inferred type.
				super.storeType(exp, cn);
				if (ve.getNodeMetaData("groovyls-original-inferred-type") == null)
					ve.setNodeMetaData("groovyls-original-inferred-type", existingInferredType);
				return;
			}
		}

		final var accessedVariable = ve.getAccessedVariable();
		ve.setAccessedVariable(null);
		super.storeType(exp, cn);
		ve.setAccessedVariable(accessedVariable);
	}

	record VariableKey(Variable v, BlockStatement bs) {
	}

	private Stack<BlockStatement> bsStack = new Stack<>();
	private final Map<VariableKey, VariableExpression> lastSeenVarExp = new HashMap<>();

	@Override
	public void visitBlockStatement(BlockStatement block) {
		bsStack.push(block);
		super.visitBlockStatement(block);
		bsStack.pop();
	}

	@Override
	public void visitVariableExpression(final VariableExpression ve) {
		super.visitVariableExpression(ve);

		// Fill in this VariableExpression's inferred type using the last seen
		// VariableExpression of the same variable name, since in our storeType
		// override above, we block the propagation of any VariableExpression's
		// inferred type to its accessed variable. This allows assignments to
		// dynamically-typed variables to provide meaning to language server
		// services!

		final var key = new VariableKey(ve.getAccessedVariable(), bsStack.empty() ? null : bsStack.peek());
		final var prevVarExp = lastSeenVarExp.get(key);

		if (prevVarExp != null) {
			if (ve.getAccessedVariable() instanceof final ASTNode an && inferredTypeHasLUB(an)) {
				// The declaring VariableExpression had its INFERRED_TYPE set during a
				// popAssignmentTracking() call, so `ve` already has an LUB type that we don't
				// want to overwrite.
				// System.out.printf(
				// 		"MySTCVisitor.visitVariableExpression: INFERRED_TYPE set during popAssignmentTracking() call, skipping overwrite for %s with existing type %s and new type %s\n",
				// 		ve, getInferredType(ve), getInferredType(prevVarExp));

				if (getInferredType(ve) == null && ClassHelper.isObjectType(getInferredType(an))) {
					// If the LUB is java.lang.Object, the STC does not care to write in the INFERRED_TYPE metadata of subsequent nodes.
					// Let's do it ourselves so that GroovyASTUtils.getTypeOfNode() doesn't fallback to an inaccurate method.
					ve.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, ClassHelper.OBJECT_TYPE);
				}
			} else {
				ve.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, getInferredType(prevVarExp));
			}
		}

		lastSeenVarExp.put(key, ve);
	}

	private static ClassNode getInferredType(final ASTNode node) {
		return node.<ClassNode>getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
	}

	private static boolean inferredTypeHasLUB(final ASTNode node) {
		return node.getNodeMetaData("groovyls-during-popAssignmentTracking") != null;
	}

	@SuppressWarnings("unchecked")
	private Map<VariableExpression, List<ClassNode>> getIfElseForWhileAssignmentTracker() {
		try {
			final var field = TypeCheckingContext.class.getDeclaredField("ifElseForWhileAssignmentTracker");
			field.setAccessible(true);
			return (Map<VariableExpression, List<ClassNode>>) field.get(typeCheckingContext);
		} catch (final Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}

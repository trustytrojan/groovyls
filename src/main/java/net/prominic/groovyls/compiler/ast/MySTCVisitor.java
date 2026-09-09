////////////////////////////////////////////////////////////////////////////////
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
// Author: trustytrojan
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls.compiler.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.tools.ParameterUtils;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.ArrayGroovyMethods;
import org.codehaus.groovy.transform.stc.Receiver;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;

public class MySTCVisitor extends StaticTypeCheckingVisitor {
	public MySTCVisitor(SourceUnit source, ClassNode classNode) {
		super(source, classNode);
	}

	private boolean poppingAssignmentTracking;

	@Override
	protected Map<VariableExpression, ClassNode> popAssignmentTracking(
			Map<VariableExpression, List<ClassNode>> oldTracker) {
		poppingAssignmentTracking = true;
		final var result = super.popAssignmentTracking(oldTracker);
		poppingAssignmentTracking = false;
		return result;
	}

	@Override
	protected void storeType(final Expression exp, ClassNode cn) {
		if (!(exp instanceof final VariableExpression ve)) {
			super.storeType(exp, cn);
			return;
		}

		if (poppingAssignmentTracking && ve.getNodeMetaData("groovyls-during-popAssignmentTracking") == null) {
			ve.setNodeMetaData("groovyls-during-popAssignmentTracking", true);
		}

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

				if (getInferredType(ve) == null && ClassHelper.isObjectType(getInferredType(an))) {
					// If the LUB is java.lang.Object, the STC does not care to write in the
					// INFERRED_TYPE metadata of subsequent nodes.
					// Let's do it ourselves so that GroovyASTUtils.getTypeOfNode() doesn't fallback
					// to an inaccurate method.
					ve.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, ClassHelper.OBJECT_TYPE);
				}
			} else {
				ve.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, getInferredType(prevVarExp));
			}
		}

		lastSeenVarExp.put(key, ve);
	}

	@Override
	protected boolean existsProperty(final PropertyExpression pexp, final boolean readMode,
			final ClassCodeVisitorSupport visitor) {
		if (super.existsProperty(pexp, readMode, visitor))
			return true;

		// Copied code from superclass for checking if a MOP method exists on the
		// receiver.

		final var objectExpression = pexp.getObjectExpression();
		final var receivers = new ArrayList<Receiver<String>>();
		addReceivers(receivers, makeOwnerList(objectExpression), pexp.isImplicitThis());

		for (final var receiver : receivers) {
			final var receiverType = receiver.getType();

			if (receiverType.isArray() || receiverType.isScriptBody()
					|| ClassHelper.isPrimitiveType(ClassHelper.getUnwrapper(receiverType)))
				continue;

			MethodNode mopMethod;

			if (readMode) {
				final var name = new Parameter[] { new Parameter(ClassHelper.STRING_TYPE, "name") };
				mopMethod = getMostDerivedMethod(receiverType, "get", name);
				if (mopMethod == null)
					mopMethod = getMostDerivedMethod(receiverType, "getProperty", name);
				if (mopMethod == null || mopMethod.isStatic() || mopMethod.isSynthetic())
					mopMethod = getMostDerivedMethod(receiverType, "propertyMissing", name);
			} else {
				final var nameAndValue = new Parameter[] {
						new Parameter(ClassHelper.STRING_TYPE, "name"),
						new Parameter(ClassHelper.OBJECT_TYPE, "value") };
				mopMethod = getMostDerivedMethod(receiverType, "set", nameAndValue);
				if (mopMethod == null)
					mopMethod = getMostDerivedMethod(receiverType, "setProperty", nameAndValue);
				if (mopMethod == null || mopMethod.isStatic() || mopMethod.isSynthetic())
					mopMethod = getMostDerivedMethod(receiverType, "propertyMissing", nameAndValue);
			}

			if (mopMethod != null && !mopMethod.isStatic() && !mopMethod.isSynthetic()) {
				pexp.putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, Boolean.TRUE);
				pexp.putNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET, mopMethod);
				pexp.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, mopMethod.getReturnType());

				if (visitor != null)
					visitor.visitMethod(mopMethod);

				return true;
			}
		}

		return false;
	}

	private static ClassNode getInferredType(final ASTNode node) {
		return node.<ClassNode>getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
	}

	private static boolean inferredTypeHasLUB(final ASTNode node) {
		return node.getNodeMetaData("groovyls-during-popAssignmentTracking") != null;
	}

	private static List<MethodNode> getMethods(final ClassNode cn, final String name,
			final Parameter[] params) {
		final var candidates = new ArrayList<MethodNode>();

		final var zeroParameters = !ArrayGroovyMethods.asBoolean(params);
		for (final var method : cn.getMethods(name)) {
			final var methodParameters = method.getParameters();
			if (zeroParameters ? methodParameters.length == 0
					: ParameterUtils.parametersCompatible(methodParameters, params)) {
				candidates.add(method);
			}
		}

		return candidates;
	}

	public static MethodNode getMostDerivedMethod(final ClassNode cn, final String name, final Parameter[] params) {
		final var declared = cn.getDeclaredMethod(name, params);
		if (declared != null)
			return declared;

		final var candidates = getMethods(cn, name, params);
		if (candidates.size() == 0)
			return null;

		var mostDerivedMethod = candidates.getFirst();

		for (final var m : candidates) {
			if (m.getReturnType().isDerivedFrom(mostDerivedMethod.getReturnType())
					|| m.getDeclaringClass().isDerivedFrom(mostDerivedMethod.getDeclaringClass()))
				mostDerivedMethod = m;
		}

		return mostDerivedMethod;
	}
}

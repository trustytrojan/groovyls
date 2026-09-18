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
package net.prominic.groovyls.util;

import java.util.function.Function;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.tools.WideningCategories.LowestUpperBoundClassNode;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.lsp.utils.Ranges;

public class GroovyNodeToStringUtils {
	public static String constructorToString(final ConstructorNode cn, final ASTNodeVisitor ast, final FileContentsTracker fct) {
		return "%s(%s)".formatted(
				cn.getDeclaringClass().getName(),
				parametersToString(cn.getParameters(), ast, fct));
	}

	public static String methodToString(final MethodNode mn, final ASTNodeVisitor ast, final FileContentsTracker fct,
			final ClassNode inferredReturnType) {
		if (mn instanceof final ConstructorNode cn) {
			return constructorToString(cn, ast, fct);
		}
		return "%s %s.%s(%s)".formatted(
				// In the case of methods that return a generic type, like
				// `T List<T>.getFirst()`, `inferredType` contains what `T` should be, so use
				// it over the method's return type which is usually just `Object`.
				prettyPrintTypeWithoutPackage((inferredReturnType != null) ? inferredReturnType : mn.getReturnType()),
				mn.getDeclaringClass().getName(),
				mn.getName(),
				parametersToString(mn.getParameters(), ast, fct));
	}

	public static String parametersToString(final Parameter[] params, final ASTNodeVisitor ast, final FileContentsTracker fct) {
		final var sb = new StringBuilder();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			final var paramNode = params[i];
			sb.append(variableToString(paramNode, ast, fct));
		}
		return sb.toString();
	}

	// Modified copy of ClassNode.toString(boolean)
	public static String prettyPrintType(final ClassNode cn, final Function<ClassNode, String> nameGetter) {
		if (cn.isArray()) {
			return nameGetter.apply(cn.getComponentType()) + "[]";
		}
		final var placeholder = cn.isGenericsPlaceHolder();
		final var ret = new StringBuilder(!placeholder ? nameGetter.apply(cn) : cn.getUnresolvedName());
		{
			final var genericsTypes = cn.getGenericsTypes();
			if (!placeholder && genericsTypes != null) {
				ret.append('<');
				for (int i = 0, n = genericsTypes.length; i < n; i += 1) {
					if (i != 0)
						ret.append(", ");
					ret.append(genericsTypes[i].getType().getNameWithoutPackage());
				}
				ret.append('>');
			}
		}
		return ret.toString();
	}

	public static String prettyPrintTypeWithoutPackage(final ClassNode cn) {
		return prettyPrintType(cn, ClassNode::getNameWithoutPackage);
	}

	public static String prettyPrintTypeWithPackage(final ClassNode cn) {
		return prettyPrintType(cn, ClassNode::getName);
	}

	public static String variableToString(final Variable v, final ASTNodeVisitor ast, final FileContentsTracker fct) {
		ClassNode cn;
		if (v instanceof final ASTNode an) {
			cn = GroovyASTUtils.getTypeOfNode(an, ast);
		} else {
			cn = v.getType();
		}
		if (cn instanceof final LowestUpperBoundClassNode lub) {
			// This bypasses a bug in LowestUpperBoundClassNode's constructor:
			// It flattens all interfaces' generics even if some interfaces
			// (the first one in particular, which is chosen as the "compileTimeClassNode")
			// are NOT generic types.
			final var upper = lub.getSuperClass();
			final var interfaces = lub.getInterfaces();
			cn = (ClassHelper.isObjectType(upper) && interfaces != null && interfaces.length > 0)
					? interfaces[0]
					: upper;
		}
		final var initialExprStr = v.hasInitialExpression()
				? " = %s".formatted(exprToString(v.getInitialExpression(), ast, fct))
				: "";
		return "%s %s%s".formatted(prettyPrintTypeWithoutPackage(cn), v.getName(), initialExprStr);
	}

	private static String exprToString(final Expression expr, final ASTNodeVisitor ast, final FileContentsTracker fct) {
		// Print string literals exactly as they were written in the source code.
		if (expr instanceof ConstantExpression && ClassHelper.isStringType(expr.getType())) {
			final var range = GroovyLanguageServerUtils.astNodeToRange(expr);
			final var uri = ast.getURI(expr);
			if (range != null && uri != null) {
				final var fileContents = fct.getContents(uri);
				if (fileContents != null) {
					final var text = Ranges.getSubstring(fileContents, range);
					if (text != null)
						return text;
				}
			}
		}
		return expr.getText();
	}
}
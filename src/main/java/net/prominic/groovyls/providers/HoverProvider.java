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
package net.prominic.groovyls.providers;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.compiler.util.GroovydocUtils;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.util.GroovyNodeToStringUtils;

public class HoverProvider extends BaseProvider {
	public HoverProvider(final ASTNodeVisitor ast, final FileContentsTracker fct) {
		super(ast, fct);
	}

	public CompletableFuture<Hover> provideHover(final TextDocumentIdentifier textDocument, final Position position,
			final FileContentsTracker fct) {
		final var uri = URI.create(textDocument.getUri());
		final var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			return CompletableFuture.completedFuture(null);
		}

		final var propOrCallExpr = (offsetNode instanceof final ConstantExpression ce)
				? GroovyASTUtils.getPropertyOrMethodCallFromConstantExpression(ce, ast)
				: null;
		final var inferredType = (propOrCallExpr != null)
				? propOrCallExpr.<ClassNode>getNodeMetaData(StaticTypesMarker.INFERRED_TYPE)
				: null;

		// System.err.print("provideHover: offsetNode: ");
		// debugPrint(offsetNode);

		final var definitionNode = getDefinition(offsetNode, false);
		if (definitionNode == null) {
			return CompletableFuture.completedFuture(null);
		}
		if (definitionNode instanceof final ClassNode cn && ClassHelper.isPrimitiveType(cn)) {
			// Eclipse JDT LS returns nothing when hovering over primitive types.
			return CompletableFuture.completedFuture(null);
		}

		// System.err.print("provideHover: definitionNode: ");
		// debugPrint(definitionNode);

		final var offsetNodeReferencesDefinitionNode = definitionNode != offsetNode
				&& offsetNode instanceof final VariableExpression ve
				&& ve.getAccessedVariable() == definitionNode;

		// In a multi-assignment scenario, only offsetNode has the correct inferred type
		// at its point in the code.
		final var content = getContent(
				offsetNodeReferencesDefinitionNode ? offsetNode : definitionNode,
				inferredType,
				fct);
		if (content == null) {
			System.err.println("*** hover not available for node: " + definitionNode);
			return CompletableFuture.completedFuture(null);
		}

		String documentation = null;
		if (definitionNode instanceof final AnnotatedNode an) {
			documentation = GroovydocUtils.groovydocToMarkdownDescription(an.getGroovydoc());
		}

		final var contentsBuilder = new StringBuilder();
		contentsBuilder.append("```groovy\n");
		contentsBuilder.append(content);
		contentsBuilder.append("\n```");
		if (documentation != null) {
			contentsBuilder.append("\n\n---\n\n");
			contentsBuilder.append(documentation);
		}

		final var contents = new MarkupContent();
		contents.setKind(MarkupKind.MARKDOWN);
		contents.setValue(contentsBuilder.toString());
		final var hover = new Hover();
		hover.setContents(contents);
		return CompletableFuture.completedFuture(hover);
	}

	private String getContent(final ASTNode hoverNode, final ClassNode inferredType, final FileContentsTracker fct) {
		return switch (hoverNode) {
			case final ClassNode cn -> GroovyNodeToStringUtils.prettyPrintTypeWithPackage(cn);
			case final MethodNode mn -> GroovyNodeToStringUtils.methodToString(mn, ast, fct, inferredType);
			case final Variable v -> GroovyNodeToStringUtils.variableToString(v, ast, fct);
			default -> null;
		};
	}
}
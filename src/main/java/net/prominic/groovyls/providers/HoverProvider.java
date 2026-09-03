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
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.compiler.util.GroovydocUtils;
import net.prominic.groovyls.util.GroovyNodeToStringUtils;

public class HoverProvider {
	private final ASTNodeVisitor ast;

	public HoverProvider(final ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<Hover> provideHover(final TextDocumentIdentifier textDocument, final Position position) {
		if (ast == null) {
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(null);
		}

		final var uri = URI.create(textDocument.getUri());
		final var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			return CompletableFuture.completedFuture(null);
		}

		// SemanticTokensProvider.debugPrint(offsetNode, null);

		var definitionNode = GroovyASTUtils.getDefinition(offsetNode, false, ast);
		// System.out.printf("provideHover: offsetNode=%s definitionNode=%s\n", offsetNode, definitionNode);
		final var offsetNodeReferencesDefinitionNode = definitionNode != offsetNode
				&& offsetNode instanceof final VariableExpression ve
				&& ve.getAccessedVariable() == definitionNode;
		if (definitionNode == null && offsetNode instanceof VariableExpression) {
			// gdsl: Lookup the variable's text as a field of the enclosing script class.
			final var enclosingClass = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ClassNode.class,
					ast);
			if (enclosingClass != null && enclosingClass.isScript()) {
				definitionNode = enclosingClass.getField(offsetNode.getText());
			}
		}
		if (definitionNode == null) {
			return CompletableFuture.completedFuture(null);
		}

		// Only offsetNode has the current inferred type for the variable at its
		// specific point in the code.
		final var content = getContent(offsetNodeReferencesDefinitionNode ? offsetNode : definitionNode);
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

	private String getContent(final ASTNode hoverNode) {
		return switch (hoverNode) {
			case final ClassNode cn -> cn.getName();
			case final MethodNode mn -> GroovyNodeToStringUtils.methodToString(mn, ast);
			case final Variable v -> GroovyNodeToStringUtils.variableToString(v, ast);
			default -> null;
		};
	}
}
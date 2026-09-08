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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ClassNode;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;

public class WorkspaceSymbolProvider {
	private final ASTNodeVisitor ast;

	public WorkspaceSymbolProvider(final ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> provideWorkspaceSymbols(
			final String query) {
		if (ast == null)
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(Either.forLeft(List.of()));

		final var lowerCaseQuery = query.toLowerCase();
		final var nodes = ast.getNodes();

		final var symbols = nodes.stream()
				.filter(node -> SemanticTokensProvider.getDeclarationName(node) instanceof final String name
						&& name.toLowerCase().contains(lowerCaseQuery))
				.map(node -> {
					final var uri = ast.getURI(node);
					if (node instanceof final ClassNode cn)
						return GroovyLanguageServerUtils.astNodeToSymbolInformation(cn, uri, null);
					final var enclosingClass = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class,
							ast);
					return GroovyLanguageServerUtils.astNodeToSymbolInformation(node, uri, enclosingClass.getName());
				})
				.filter(Objects::nonNull)
				.toList();

		return CompletableFuture.completedFuture(Either.forLeft(symbols));
	}
}
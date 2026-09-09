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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ClassNode;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;

public class DocumentSymbolProvider {
	private final ASTNodeVisitor ast;

	public DocumentSymbolProvider(final ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> provideDocumentSymbols(
			final TextDocumentIdentifier textDocument) {
		if (ast == null)
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(List.of());

		final var uri = URI.create(textDocument.getUri());
		final var nodes = ast.getNodes(uri);

		return CompletableFuture.completedFuture(nodes.stream()
				.map(node -> {
					if (node instanceof final ClassNode cn)
						return GroovyLanguageServerUtils.astNodeToSymbolInformation(cn, uri, null);
					final var enclosingClass = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class,
							ast);
					return (enclosingClass != null)
							? GroovyLanguageServerUtils.astNodeToSymbolInformation(node, uri, enclosingClass.getName())
							: null;
				})
				.filter(Objects::nonNull)
				.map(Either::<SymbolInformation, DocumentSymbol>forLeft)
				.toList());
	}
}
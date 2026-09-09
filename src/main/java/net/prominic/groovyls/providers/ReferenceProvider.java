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

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;

public class ReferenceProvider {
	private final ASTNodeVisitor ast;

	public ReferenceProvider(final ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<List<? extends Location>> provideReferences(final TextDocumentIdentifier textDocument,
			final Position position) {
		if (ast == null)
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(List.of());

		final var documentURI = URI.create(textDocument.getUri());
		final var offsetNode = ast.getNodeAtLineAndColumn(documentURI, position.getLine(), position.getCharacter());

		if (offsetNode == null)
			return CompletableFuture.completedFuture(List.of());

		final var references = GroovyASTUtils.getReferences(offsetNode, ast);

		return CompletableFuture.completedFuture(references.stream()
				.map(node -> {
					final var uri = ast.getURI(node);
					return (uri != null) ? GroovyLanguageServerUtils.astNodeToLocation(node, uri) : null;
				})
				.filter(Objects::nonNull)
				.toList());
	}
}
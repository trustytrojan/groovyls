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
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;

public class DefinitionProvider extends BaseProvider {
	public DefinitionProvider(final ASTNodeVisitor ast, final FileContentsTracker fct) {
		super(ast, fct);
	}

	private static CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> makeReturnValue(
			final Location... list) {
		return CompletableFuture.completedFuture(Either.forLeft(List.of(list)));
	}

	private static final CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> SENTINEL = makeReturnValue();

	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> provideDefinition(
			final TextDocumentIdentifier textDocument,
			final Position position,
			final FileContentsTracker fct) {
		if (ast == null) {
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return SENTINEL;
		}
		final var uri = URI.create(textDocument.getUri());
		final var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			return SENTINEL;
		}

		// System.err.print("provideDefinition: offsetNode: ");
		// debugPrint(offsetNode);

		var definitionNode = getDefinition(offsetNode, true);
		if (definitionNode == null) {
			return SENTINEL;
		}

		// System.err.print("provideDefinition: definitionNode: ");
		// debugPrint(definitionNode);

		var definitionURI = ast.getURI(definitionNode);
		if (definitionURI == null) {
			return SENTINEL;
		}

		var location = GroovyLanguageServerUtils.astNodeToLocation(definitionNode, definitionURI);
		if (location == null) {
			if (definitionNode instanceof final ConstructorNode cn) {
				// The constructor's class has no explicit constructor. Refer to the entire
				// class.
				definitionNode = cn.getDeclaringClass();
				definitionURI = ast.getURI(definitionNode);
			}
			if (definitionNode instanceof ClassNode) {
				// For some reason, ClassNodes of script classes (no explicit class body) have
				// no range information.
				// Refer to the script file in the simplest way possible.
				location = new Location(definitionURI.toString(), new Range(new Position(), new Position(0, 1)));
				return makeReturnValue(location);
			}
			return SENTINEL;
		}

		return makeReturnValue(location);
	}
}
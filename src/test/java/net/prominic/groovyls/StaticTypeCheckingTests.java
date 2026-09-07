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
package net.prominic.groovyls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.prominic.groovyls.config.CompilationUnitFactory;

/**
 * @implNote This file is formatted with hard tabs which count as 1 column in
 *           terms of LSP positioning.
 */
public class StaticTypeCheckingTests {
	private static final String LANGUAGE_GROOVY = "groovy";
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_SRC = "./src/main/groovy";

	private GroovyServices services;
	private Path workspaceRoot;
	private Path srcRoot;
	private TextDocumentIdentifier textDocument;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		srcRoot = workspaceRoot.resolve(PATH_SRC);
		if (!Files.exists(srcRoot)) {
			srcRoot.toFile().mkdirs();
		}

		services = new GroovyServices(new CompilationUnitFactory());
		services.setWorkspaceRoot(workspaceRoot);
		services.connect(new LanguageClient() {
			@Override
			public void telemetryEvent(Object object) {
			}

			@Override
			public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
				return null;
			}

			@Override
			public void showMessage(MessageParams messageParams) {
			}

			@Override
			public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
			}

			@Override
			public void logMessage(MessageParams message) {
			}
		});
	}

	@AfterEach
	void tearDown() {
		services = null;
		workspaceRoot = null;
		srcRoot = null;
		textDocument = null;
	}

	private String getHoverContentAtPosition(final int line, final int col) throws Exception {
		if (textDocument == null)
			throw new IllegalStateException("textDocument is null");
		final var position = new Position(line, col);
		final var result = services.hover(new HoverParams(textDocument, position)).get();
		final var hoverContents = result.getContents();
		return hoverContents.getRight().getValue().replace("```groovy\n", "").replace("\n```", "");
	}

	private void openTextDocument(final String contents) {
		final var filePath = srcRoot.resolve("TypeCheckingTest.groovy");
		final var uri = filePath.toUri().toString();
		final var textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		textDocument = new TextDocumentIdentifier(uri);
	}

	@Test
	void testVariableInferredTypePropagatesToSubsequentVariables() throws Exception {
		openTextDocument("""
				def x = 3
				def y = x
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "int y");
		Assertions.assertEquals(getHoverContentAtPosition(1, 8), "int x");
	}

	@Test
	void testVariableInferredTypeChangesOnAssignment() throws Exception {
		openTextDocument("""
				def x = 3
				def y = (x = '')
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 0), "java.lang.String");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "String y");
		Assertions.assertEquals(getHoverContentAtPosition(1, 9), "String x");
	}

	@Test
	void testVariableInferredTypeBecomesLUBAfterIfStatement_Primitives() throws Exception {
		openTextDocument("""
				def x = 3
				if (x == 3)
					x = 4.0f
				x
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(2, 1), "float x");
		Assertions.assertEquals(getHoverContentAtPosition(3, 0), "float x");
	}

	@Test
	void testVariableInferredTypeBecomesLUBAfterIfElseStatement_Primitives() throws Exception {
		openTextDocument("""
				def x = 3
				if (x == 3)
					x = 4.0f
				else
					x = 4.0d
				x
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(2, 1), "float x");
		Assertions.assertEquals(getHoverContentAtPosition(4, 1), "double x");
		Assertions.assertEquals(getHoverContentAtPosition(5, 0), "double x");
	}

	@Test
	void testVariableInferredTypeBecomesLUBAfterIfStatement_Serializable() throws Exception {
		openTextDocument("""
				def x = 3
				if (x == 3)
					x = ''
				x
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(2, 1), "String x");
		Assertions.assertEquals(getHoverContentAtPosition(3, 0), "Serializable x");
	}

	@Test
	void testVariableInferredTypeBecomesLUBAfterIfStatement_Object() throws Exception {
		openTextDocument("""
				def x = 3
				if (x == 3)
					x = new Object()
				x
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(2, 1), "Object x");
		Assertions.assertEquals(getHoverContentAtPosition(3, 0), "Object x");
	}

	@Test
	void testVariableInferredTypeBecomesLUBAfterIfStatement_Number() throws Exception {
		openTextDocument("""
				def x = 3
				if (x == 3)
					x = 4.0
				x
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "int");
		Assertions.assertEquals(getHoverContentAtPosition(0, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(1, 4), "int x");
		Assertions.assertEquals(getHoverContentAtPosition(2, 1), "BigDecimal x");
		Assertions.assertEquals(getHoverContentAtPosition(3, 0), "Number x");
	}

	@Test
	void testCallableObject() throws Exception {
		openTextDocument("""
				class A {
					def call() {}
				}
				def obj = new A()
				obj()
				""");

		Assertions.assertEquals(getHoverContentAtPosition(0, 0), "A");
		Assertions.assertEquals(getHoverContentAtPosition(1, 1), "Object A.call()");
		Assertions.assertEquals(getHoverContentAtPosition(3, 0), "A");
		Assertions.assertEquals(getHoverContentAtPosition(3, 4), "A obj");
		Assertions.assertEquals(getHoverContentAtPosition(4, 0), "Object A.call()");
	}
}

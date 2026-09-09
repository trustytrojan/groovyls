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
package net.prominic.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.ast.MySTCVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;
import net.prominic.lsp.utils.Ranges;

public class SemanticTokensProvider {
	private final FileContentsTracker fileContentsTracker;

	public static enum SemanticTokenTypes {
		TYPE("type"),
		CLASS("class"),
		ENUM("enum"),
		INTERFACE("interface"),
		STRUCT("struct"),
		TYPE_PARAMETER("typeParameter"),
		PARAMETER("parameter"),
		VARIABLE("variable"),
		PROPERTY("property"),
		ENUM_MEMBER("enumMember"),
		EVENT("event"),
		FUNCTION("function"),
		METHOD("method"),
		MACRO("macro"),
		KEYWORD("keyword"),
		MODIFIER("modifier"),
		COMMENT("comment"),
		STRING("string"),
		NUMBER("number"),
		REGEXP("regexp"),
		OPERATOR("operator"),
		DECORATOR("decorator"),
		LABEL("label");

		public final String value;

		private SemanticTokenTypes(final String s) {
			value = s;
		}

		public static List<String> getList() {
			return Stream.of(values()).map(t -> t.value).toList();
		}
	}

	public static enum SemanticTokenModifiers {
		DECLARATION("declaration"),
		DEFINITION("definition"),
		READONLY("readonly"),
		STATIC("static"),
		DEPRECATED("deprecated"),
		ABSTRACT("abstract"),
		ASYNC("async"),
		MODIFICATION("modification"),
		DOCUMENTATION("documentation"),
		DEFAULT_LIBRARY("defaultLibrary");

		public final String value;

		private SemanticTokenModifiers(final String s) {
			value = s;
		}

		public static List<String> getList() {
			return Stream.of(values()).map(t -> t.value).toList();
		}

		public static int bitset(final SemanticTokenModifiers... modifiers) {
			var bitset = 0;
			for (final var m : modifiers)
				bitset |= (1 << m.ordinal());
			return bitset;
		}
	}

	private final ASTNodeVisitor astVisitor;

	public SemanticTokensProvider(final FileContentsTracker fileContentsTracker, final ASTNodeVisitor astVisitor) {
		this.fileContentsTracker = fileContentsTracker;
		this.astVisitor = astVisitor;
	}

	private Token makeTokenFromRange(final Range r, final int type, final int modifiers) {
		final var start = r.getStart();
		final var startLine = start.getLine();
		final var startChar = start.getCharacter();
		final var endChar = r.getEnd().getCharacter();
		return new Token(startLine, startChar, endChar - startChar, type, modifiers);
	}

	public static void debugPrint(final ASTNode expr, final String text) {
		if (expr instanceof final Expression e && e.isSynthetic()) {
			return;
		}

		System.err.printf("debugPrint: %s\n  text: '%s'\n", expr, expr.getText());

		if (expr.getNodeMetaData("groovyls-original-inferred-type") instanceof final ClassNode cn) {
			System.err.printf("  original_inferred_type: %s\n", cn);
		}

		if (expr.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE) instanceof final ClassNode cn) {
			System.err.printf("  inferred_type: %s\n", cn);
		}

		if (expr.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE) instanceof final ClassNode cn) {
			System.err.printf("  inferred_return_type: %s\n", cn);
		}

		if (expr.getNodeMetaData(StaticTypesMarker.DECLARATION_INFERRED_TYPE) instanceof final ClassNode cn) {
			System.err.printf("  declaration_inferred_type: %s\n", cn);
		}

		if (expr.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET) instanceof final MethodNode mn) {
			System.err.printf("  direct_method_call_target: %s\n", mn);
		}

		if (expr.getNodeMetaData(StaticTypesMarker.READONLY_PROPERTY) instanceof final Boolean b) {
			System.err.printf("  readonly_property: %s\n", b);
		}

		if (expr instanceof final Expression e) {
			System.err.printf("  type: %s\n", e.getType());
		} else if (expr instanceof final Variable v) {
			System.err.printf("  type: %s\n", v.getType());
		}

		if (expr instanceof final VariableExpression ve) {
			System.err.printf("  accessed_variable: %s\n", ve.getAccessedVariable());
		}

		if (expr instanceof final MethodCallExpression mce) {
			System.err.printf("  method_target: %s\n", mce.getMethodTarget());
		}

		if (expr instanceof final Variable v) {
			System.err.printf("  initial_expression: %s\n  is_final: %s\n  is_dynamic_typed: %s\n",
					v.getInitialExpression(),
					v.isFinal(),
					v.isDynamicTyped());
		}

		if (expr instanceof final MethodNode me) {
			System.err.printf("  return_type: %s\n", me.getReturnType());
		}

		if (text != null) {
			final var r = GroovyLanguageServerUtils.astNodeToRange(expr);
			if (r != null)
				System.err.printf("  range_to_text: '%s'\n", Ranges.getSubstring(text, r));
		}
	}

	private String currentDocumentText;

	@SuppressWarnings("null")
	public SemanticTokens provideFull(final TextDocumentIdentifier textDocument) {
		final var uri = URI.create(textDocument.getUri());
		currentDocumentText = fileContentsTracker.getContents(uri);

		if (currentDocumentText == null || astVisitor == null || uri == null)
			return new SemanticTokens(new ArrayList<>());

		final var tokens = new ArrayList<Token>();

		// System.err.println("--- Start of text document: " + uri);
		for (final var node : astVisitor.getNodes(uri)) {
			// debugPrint(node, currentDocumentText);

			if (node instanceof final ConstructorCallExpression cce) {
				final var type = cce.getType();
				if (type.equals(ClassHelper.OBJECT_TYPE))
					continue;
				final var r = GroovyLanguageServerUtils.astNodeToRange(type);
				if (r == null)
					continue;
				tokens.add(makeTokenFromRange(r, SemanticTokenTypes.METHOD.ordinal(), 0));
			} else if (node instanceof final DeclarationExpression de) {
				final var ve = de.getVariableExpression();
				if (ve == null)
					continue;
				final var r = GroovyLanguageServerUtils.astNodeToRange(ve.getOriginType());
				if (r == null)
					continue;
				tokens.add(makeTokenFromRange(r, SemanticTokenTypes.TYPE.ordinal(), 0));
			} else if (node instanceof final MethodCallExpression mce) {
				processMethodCall(mce, tokens);
			} else if (node instanceof final PropertyExpression pe) {
				processPropertyExpression(pe, tokens);
			} else {
				processDeclaration(node, tokens);
			}
		}

		if (tokens.isEmpty())
			return new SemanticTokens(new ArrayList<>());

		tokens.sort(Comparator.comparingInt(Token::line).thenComparingInt(Token::startChar));

		return Token.encodeList(tokens);
	}

	private void processMethodCall(final MethodCallExpression mce, final List<Token> tokens) {
		// Properly color in a callable object as a method if it is being called
		// directly in the source code.
		final var typeOfNode = GroovyASTUtils.getTypeOfNode(mce.getObjectExpression(), astVisitor);
		final var hasCallMethod = (typeOfNode != null) && typeOfNode.hasPossibleMethod("call", mce.getArguments());
		final var callRange = GroovyLanguageServerUtils.astNodeToRange(mce);
		final var notExplicitCallMethodCall = (callRange != null)
				&& !Ranges.getSubstring(currentDocumentText, callRange).matches(".*\\.\\s*call\\s*\\(.*");

		final var callableObject = hasCallMethod && notExplicitCallMethodCall;

		final var methodText = callableObject ? mce.getObjectExpression().getText() : mce.getMethodAsString();

		// We don't want to color in expressions that evaluate to a callable.
		if (methodText == null || methodText.isEmpty() || (methodText.startsWith("(") && methodText.endsWith(")")))
			return;

		var actualMethod = mce.<MethodNode>getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
		if (actualMethod == null)
			actualMethod = mce.getMethodTarget();
		if (actualMethod == null)
			actualMethod = GroovyASTUtils.getMethodFromCallExpression(mce, astVisitor);
		if (actualMethod == null)
			return;

		// If call is `obj.func()`, then this range spans `func`.
		final var range = GroovyLanguageServerUtils.astNodeToRange(mce.getMethod());
		if (range == null)
			return;

		final var lineno = range.getStart().getLine();
		final var charno = range.getStart().getCharacter();

		tokens.add(new Token(lineno, charno, methodText.length(), SemanticTokenTypes.METHOD.ordinal(),
				getModifiersOfNode(actualMethod)));
	}

	private int getModifiersOfNode(final ASTNode node) {
		final var modifiers = new ArrayList<SemanticTokenModifiers>();
		switch (node) {
			case final MethodNode mn -> {
				if (mn.isAbstract())
					modifiers.add(SemanticTokenModifiers.ABSTRACT);
				if (mn.isStatic())
					modifiers.add(SemanticTokenModifiers.STATIC);
			}
			case final Variable v -> {
				if (v.isStatic())
					modifiers.add(SemanticTokenModifiers.STATIC);
				if (v.isFinal())
					modifiers.add(SemanticTokenModifiers.READONLY);
			}
			default -> {
			}
		}
		return SemanticTokenModifiers.bitset(modifiers.toArray(SemanticTokenModifiers[]::new));
	}

	private void processPropertyExpression(final PropertyExpression pe, final List<Token> tokens) {
		// propName and propRange represent the `prop` part of `obj.prop`.
		final var propName = pe.getPropertyAsString();
		if (propName == null || propName.isEmpty())
			return;

		final var propRange = GroovyLanguageServerUtils.astNodeToRange(pe.getProperty());
		if (propRange == null)
			return;

		final var lineno = propRange.getStart().getLine();
		var charno = propRange.getStart().getCharacter();

		if (astVisitor.getParent(pe) instanceof final GStringExpression gse) {
			final var r = GroovyLanguageServerUtils.astNodeToRange(gse);
			if (r != null) {
				final var sourceText = Ranges.getSubstring(currentDocumentText, r);
				if (sourceText.contains('$' + pe.getText()))
					// This PropertyExpression is inside a GStringExpression like this:
					// "value: $obj.value". The PropertyExpression's range starts at the '$' but
					// does not count it as length...
					++charno;
			}
		}

		if (pe.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET) instanceof final MethodNode methodNode) {
			var modifiers = 0;

			final var declaringClass = methodNode.getDeclaringClass();

			// GroovyObject subclasses always have both a getProperty() and setProperty().
			if (!declaringClass.isDerivedFromGroovyObject()) {
				final var methodName = methodNode.getName();
				if (methodName.startsWith("get")) {
					// We need to see if there is a setter with the same name that takes 1 argument.

					final var valueType = methodNode.getReturnType();
					var params = new Parameter[] { new Parameter(valueType, "") };
					var setter = declaringClass.getDeclaredMethod(methodName.replaceFirst("get", "set"), params);

					// Try the Groovy MOP methods: "set", "setProperty", and "propertyMissing"
					if (setter == null) {
						params = new Parameter[] { new Parameter(ClassHelper.STRING_TYPE, ""),
								new Parameter(ClassHelper.OBJECT_TYPE, "") };
						setter = MySTCVisitor.getMostDerivedMethod(declaringClass, "set", params);
					}

					if (setter == null)
						setter = MySTCVisitor.getMostDerivedMethod(declaringClass, "setProperty", params);

					if (setter == null)
						setter = MySTCVisitor.getMostDerivedMethod(declaringClass, "propertyMissing", params);

					if (setter == null)
						// No setter: the property can be considered "readonly".
						modifiers = SemanticTokenModifiers.bitset(SemanticTokenModifiers.READONLY);
				}
			}

			tokens.add(new Token(lineno, charno, propName.length(), SemanticTokenTypes.PROPERTY.ordinal(), modifiers));
		}

		// Use these utility functions because they also take into account member
		// visibility.
		final var fieldNode = GroovyASTUtils.getFieldFromExpression(pe, astVisitor);
		final var propertyNode = GroovyASTUtils.getPropertyFromExpression(pe, astVisitor);

		var modifiers = 0;
		if (fieldNode != null)
			modifiers = getModifiersOfNode(fieldNode);
		else if (propertyNode != null)
			modifiers = getModifiersOfNode(propertyNode);

		tokens.add(new Token(lineno, charno, propName.length(), SemanticTokenTypes.PROPERTY.ordinal(), modifiers));
	}

	// probably should be named `processSymbol` and/or should be split up by type a
	// bit more
	private void processDeclaration(final ASTNode node, final List<Token> tokens) {
		if (node instanceof final MethodNode mn && mn.isConstructor()) {
			processConstructorDeclaration(mn, tokens);
			return;
		}

		final var name = getDeclarationName(node);
		if (name == null || name.equals("this") || name.equals("super"))
			return;

		final var identifierRange = findIdentifierRange(node, name);
		if (identifierRange == null)
			return;

		final var tokenType = tokenTypeIndexFromNode(node);
		final var modifiers = getModifiersOfNode(node);

		tokens.add(new Token(identifierRange.getStart().getLine(), identifierRange.getStart().getCharacter(),
				name.length(), tokenType, modifiers));
	}

	private int tokenTypeIndexFromNode(final ASTNode node) {
		if (node instanceof MethodNode
				|| ClassHelper.CLOSURE_TYPE.equals(GroovyASTUtils.getTypeOfNode(node, astVisitor)))
			return SemanticTokenTypes.FUNCTION.ordinal();
		if (node instanceof ClassNode || node instanceof ImportNode)
			return SemanticTokenTypes.CLASS.ordinal();
		if (node instanceof FieldNode || node instanceof PropertyNode)
			return SemanticTokenTypes.PROPERTY.ordinal();
		return SemanticTokenTypes.VARIABLE.ordinal();
	}

	private void processConstructorDeclaration(final MethodNode mn, final List<Token> tokens) {
		final var declaringClass = mn.getDeclaringClass();
		if (declaringClass == null)
			return;

		final var className = declaringClass.getNameWithoutPackage();
		if (className == null || className.isEmpty())
			return;

		final var identifierRange = findIdentifierRange(mn, className);
		if (identifierRange == null)
			return;

		tokens.add(new Token(identifierRange.getStart().getLine(), identifierRange.getStart().getCharacter(),
				className.length(), SemanticTokenTypes.METHOD.ordinal(), getModifiersOfNode(mn)));
	}

	public static String getDeclarationName(final ASTNode node) {
		return switch (node) {
			case final MethodNode mn -> mn.getName();
			case final Variable v -> v.getName();
			case final ClassNode cn -> cn.getName();
			case final ImportNode in -> in.getClassName();
			default -> null;
		};
	}

	private Range findIdentifierRange(final ASTNode node, final String name) {
		final var text = currentDocumentText;

		final var nodeRange = GroovyLanguageServerUtils.astNodeToRange(node);
		if (nodeRange == null)
			return null;

		final var startOffset = lineColToOffset(text, nodeRange.getStart().getLine(),
				nodeRange.getStart().getCharacter());
		final var endOffset = lineColToOffset(text, nodeRange.getEnd().getLine(), nodeRange.getEnd().getCharacter());
		if (startOffset < 0 || endOffset <= startOffset)
			return null;

		final var identifierOffset = findExactTokenOffset(text, name, startOffset, endOffset);
		if (identifierOffset < 0)
			return null;

		final var start = toLineCol(text, identifierOffset);
		final var end = toLineCol(text, identifierOffset + name.length());
		return new Range(start, end);
	}

	private int findExactTokenOffset(final String text, final String name, final int startOffset, final int endOffset) {
		var found = startOffset;
		while (found >= 0) {
			found = text.indexOf(name, found);
			if (found == -1 || found >= endOffset)
				return -1;
			final var beforeValid = (found == 0) || !Character.isJavaIdentifierPart(text.charAt(found - 1));
			final var afterValid = (found + name.length() >= text.length())
					|| !Character.isJavaIdentifierPart(text.charAt(found + name.length()));
			if (beforeValid && afterValid)
				return found;
			found++;
		}
		return -1;
	}

	private int lineColToOffset(final String text, final int line, final int col) {
		if (line < 0)
			return -1;
		int curLine = 0, offset = 0, len = text.length();
		while (offset < len && curLine < line) {
			if (text.charAt(offset) == '\n')
				curLine++;
			offset++;
		}
		if (curLine != line)
			return -1;
		return Math.min(offset + col, len);
	}

	private static record Token(int line, int startChar, int length, int type, int modifiers) {
		static SemanticTokens encodeList(final List<Token> tokens) {
			final var data = new ArrayList<Integer>();
			int prevLine = 0, prevChar = 0;
			var first = true;
			for (final var t : tokens) {
				final var deltaLine = first ? t.line : t.line - prevLine;
				final var deltaStart = first ? t.startChar : (deltaLine == 0 ? t.startChar - prevChar : t.startChar);
				data.add(deltaLine);
				data.add(deltaStart);
				data.add(t.length);
				data.add(t.type);
				data.add(t.modifiers);

				prevLine = t.line;
				prevChar = t.startChar;
				first = false;
			}
			return new SemanticTokens(data);
		}
	}

	private Position toLineCol(final String text, final int offset) {
		int line = 0, col = 0, i = 0;
		while (i < offset) {
			final var c = text.charAt(i);
			if (c == '\n') {
				line++;
				col = 0;
			} else {
				col++;
			}
			i++;
		}
		return new Position(line, col);
	}
}
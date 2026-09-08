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

import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.codehaus.groovy.ast.ClassNode;

import net.prominic.groovyls.compiler.util.GroovyASTUtils;

import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
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

		private SemanticTokenTypes(String s) {
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

		private SemanticTokenModifiers(String s) {
			value = s;
		}

		public static List<String> getList() {
			return Stream.of(values()).map(t -> t.value).toList();
		}

		public static int bitset(SemanticTokenModifiers... modifiers) {
			int bitset = 0;
			for (SemanticTokenModifiers m : modifiers) {
				bitset |= (1 << m.ordinal());
			}
			return bitset;
		}
	}

	private final ASTNodeVisitor astVisitor;

	public SemanticTokensProvider(FileContentsTracker fileContentsTracker, ASTNodeVisitor astVisitor) {
		this.fileContentsTracker = fileContentsTracker;
		this.astVisitor = astVisitor;
	}

	private Token makeTokenFromRange(Range r, int type, int modifiers) {
		final Position start = r.getStart();
		final int startLine = start.getLine();
		final int startChar = start.getCharacter();
		final int endChar = r.getEnd().getCharacter();
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
					Modifier.isFinal(v.getModifiers()),
					v.isDynamicTyped());
		}

		if (expr instanceof final MethodNode me) {
			System.err.printf("  return_type: %s\n", me.getReturnType());
		}

		if (text != null && GroovyLanguageServerUtils.astNodeToRange(expr) instanceof final Range r) {
			System.err.printf("  range_to_text: '%s'\n", Ranges.getSubstring(text, r));
		}
	}

	private String currentDocumentText;

	public SemanticTokens provideFull(TextDocumentIdentifier textDocument) {
		URI uri = URI.create(textDocument.getUri());
		String text = currentDocumentText = fileContentsTracker.getContents(uri);
		if (text == null || astVisitor == null || uri == null) {
			return new SemanticTokens(new ArrayList<>());
		}

		List<Token> tokens = new ArrayList<>();
		List<ASTNode> nodes = astVisitor.getNodes(uri);

		// System.err.println("--- Start of text document: " + uri);
		for (ASTNode node : nodes) {
			// debugPrint(node, text);

			if (node instanceof ConstructorCallExpression) {
				ClassNode type = ((ConstructorCallExpression) node).getType();
				if (type.equals(ClassHelper.OBJECT_TYPE))
					continue;
				final Range r = GroovyLanguageServerUtils.astNodeToRange(type);
				if (r == null)
					continue;
				tokens.add(makeTokenFromRange(r, SemanticTokenTypes.METHOD.ordinal(), 0));
			} else if (node instanceof final DeclarationExpression de
					&& de.getVariableExpression() instanceof final VariableExpression ve) {
				ClassNode type = ve.getOriginType();
				final Range r = GroovyLanguageServerUtils.astNodeToRange(type);
				if (r == null)
					continue;
				tokens.add(makeTokenFromRange(r, SemanticTokenTypes.TYPE.ordinal(), 0));
			} else if (node instanceof MethodCallExpression) {
				processMethodCall((MethodCallExpression) node, tokens);
			} else if (node instanceof PropertyExpression) {
				processPropertyExpression((PropertyExpression) node, tokens);
			} else {
				processDeclaration(node, text, tokens);
			}
		}

		if (tokens.isEmpty()) {
			return new SemanticTokens(new ArrayList<>());
		}

		tokens.sort(Comparator.comparingInt((Token t) -> t.line).thenComparingInt(t -> t.startChar));

		return encodeDeltaTokens(tokens);
	}

	private void processMethodCall(final MethodCallExpression mce, final List<Token> tokens) {
		// Properly color in a callable object as a method if it is being called
		// directly in the source code.
		final var callableObject =
		// @formatter:off
				GroovyASTUtils.getTypeOfNode(mce.getObjectExpression(), astVisitor) instanceof final ClassNode cn
				&& cn.hasPossibleMethod("call", mce.getArguments())
				&& GroovyLanguageServerUtils.astNodeToRange(mce) instanceof final Range r
				&& !Ranges.getSubstring(currentDocumentText, r).matches(".*\\.\\s*call\\s*\\(.*");
		// @formatter:on

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
				getModifiersOfMethod(actualMethod)));
	}

	private int getModifiersOfMethod(MethodNode method) {
		List<SemanticTokenModifiers> modifiers = new ArrayList<>();
		if (method.isAbstract())
			modifiers.add(SemanticTokenModifiers.ABSTRACT);
		if (method.isStatic())
			modifiers.add(SemanticTokenModifiers.STATIC);
		return SemanticTokenModifiers.bitset(modifiers.toArray(SemanticTokenModifiers[]::new));
	}

	private int getModifiersOfField(FieldNode field) {
		List<SemanticTokenModifiers> modifiers = new ArrayList<>();
		if (field.isStatic())
			modifiers.add(SemanticTokenModifiers.STATIC);
		if (field.isFinal())
			modifiers.add(SemanticTokenModifiers.READONLY);
		return SemanticTokenModifiers.bitset(modifiers.toArray(SemanticTokenModifiers[]::new));
	}

	private int getModifiersOfProperty(PropertyNode property) {
		List<SemanticTokenModifiers> modifiers = new ArrayList<>();
		if (property.isStatic())
			modifiers.add(SemanticTokenModifiers.STATIC);
		if (Modifier.isFinal(property.getModifiers()))
			modifiers.add(SemanticTokenModifiers.READONLY);
		return SemanticTokenModifiers.bitset(modifiers.toArray(SemanticTokenModifiers[]::new));
	}

	private int getModifiersOfVariable(VariableExpression ve) {
		List<SemanticTokenModifiers> modifiers = new ArrayList<>();
		if (Modifier.isFinal(ve.getAccessedVariable().getModifiers()))
			modifiers.add(SemanticTokenModifiers.READONLY);
		return SemanticTokenModifiers.bitset(modifiers.toArray(SemanticTokenModifiers[]::new));
	}

	private void processPropertyExpression(PropertyExpression pe, List<Token> tokens) {
		// propName and propRange represent the `prop` part of `obj.prop`.
		final var propName = pe.getPropertyAsString();
		if (propName == null || propName.isEmpty())
			return;

		final var propRange = GroovyLanguageServerUtils.astNodeToRange(pe.getProperty());
		if (propRange == null)
			return;

		final var lineno = propRange.getStart().getLine();
		var charno = propRange.getStart().getCharacter();

		if (astVisitor.getParent(pe) instanceof final GStringExpression gse
				&& GroovyLanguageServerUtils.astNodeToRange(gse) instanceof final Range r) {
			final var sourceText = Ranges.getSubstring(currentDocumentText, r);
			if (sourceText.contains('$' + pe.getText()))
				// This PropertyExpression is inside a GStringExpression like this:
				// "value: $obj.value". The PropertyExpression's range starts at the '$' but
				// does not count it as length...
				++charno;
		}

		// Use these utility functions because they also take into account member
		// visibility.
		final var fieldNode = GroovyASTUtils.getFieldFromExpression(pe, astVisitor);
		final var propertyNode = GroovyASTUtils.getPropertyFromExpression(pe, astVisitor);

		if (fieldNode == null && propertyNode == null) {
			final var methodNode = pe.<MethodNode>getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);

			if (methodNode == null)
				// The STC **should** always fill in the method for PropertyExpressions
				// that match getters/setters.
				return;

			var modifiers = 0;

			final var methodName = methodNode.getName();
			if (methodName.startsWith("get")) {
				// We need to see if there is a setter with the same name that takes 1 argument.
				final var setter = methodNode.getDeclaringClass().getDeclaredMethod(
						methodName.replaceFirst("get", "set"),
						new Parameter[] { new Parameter(methodNode.getReturnType(), null) });
				if (setter == null)
					// If not, then the property can be considered "readonly".
					modifiers = SemanticTokenModifiers.bitset(SemanticTokenModifiers.READONLY);
			}

			tokens.add(new Token(lineno, charno, propName.length(), SemanticTokenTypes.PROPERTY.ordinal(), modifiers));
			return;
		}

		var modifiers = 0;
		if (fieldNode != null)
			modifiers = getModifiersOfField(fieldNode);
		else if (propertyNode != null)
			modifiers = getModifiersOfProperty(propertyNode);

		tokens.add(new Token(lineno, charno, propName.length(), SemanticTokenTypes.PROPERTY.ordinal(), modifiers));
	}

	// probably should be named `processSymbol` and/or should be split up by type a
	// bit more
	private void processDeclaration(ASTNode node, String text, List<Token> tokens) {
		Range range = GroovyLanguageServerUtils.astNodeToRange(node);
		if (range == null)
			return;

		if (node instanceof MethodNode && ((MethodNode) node).isConstructor()) {
			processConstructorDeclaration((MethodNode) node, text, range, tokens);
			return;
		}

		String name = getDeclarationName(node);
		if (name == null || "this".equals(name) || "super".equals(name))
			return;

		int startOffset = lineColToOffset(text, range.getStart().getLine(), range.getStart().getCharacter());
		int endOffset = lineColToOffset(text, range.getEnd().getLine(), range.getEnd().getCharacter());
		if (startOffset < 0 || endOffset <= startOffset)
			return;

		int found = findExactTokenOffset(text, name, startOffset, endOffset);
		if (found == -1)
			return;

		Position pos = toLineCol(text, found);
		int tokenType = tokenTypeIndexFromNode(node);

		int modifiers = 0;
		if (node instanceof FieldNode)
			modifiers = getModifiersOfField((FieldNode) node);
		else if (node instanceof PropertyNode)
			modifiers = getModifiersOfProperty((PropertyNode) node);
		else if (node instanceof MethodNode)
			modifiers = getModifiersOfMethod((MethodNode) node);
		else if (node instanceof VariableExpression)
			modifiers = getModifiersOfVariable((VariableExpression) node);

		tokens.add(new Token(pos.getLine(), pos.getCharacter(), name.length(), tokenType, modifiers));
	}

	private int tokenTypeIndexFromNode(ASTNode node) {
		if (node instanceof MethodNode
				|| ClassHelper.CLOSURE_TYPE.equals(GroovyASTUtils.getTypeOfNode(node, astVisitor)))
			return SemanticTokenTypes.FUNCTION.ordinal();
		if (node instanceof ClassNode || node instanceof ImportNode)
			return SemanticTokenTypes.CLASS.ordinal();
		if (node instanceof FieldNode || node instanceof PropertyNode)
			return SemanticTokenTypes.PROPERTY.ordinal();
		return SemanticTokenTypes.VARIABLE.ordinal();
	}

	private void processConstructorDeclaration(MethodNode mn, String text, Range range, List<Token> tokens) {
		ClassNode declaringClass = mn.getDeclaringClass();
		String className = declaringClass != null ? declaringClass.getNameWithoutPackage() : null;
		if (className == null || className.isEmpty())
			return;

		int startOffsetCtor = lineColToOffset(text, range.getStart().getLine(), range.getStart().getCharacter());
		int endOffsetCtor = lineColToOffset(text, range.getEnd().getLine(), range.getEnd().getCharacter());

		int foundCtor = findExactTokenOffset(text, className, startOffsetCtor, endOffsetCtor);
		if (foundCtor == -1)
			return;

		Position posCtor = toLineCol(text, foundCtor);
		int tokenTypeCtor = SemanticTokenTypes.METHOD.ordinal();
		tokens.add(new Token(posCtor.getLine(), posCtor.getCharacter(), className.length(), tokenTypeCtor,
				getModifiersOfMethod(mn)));
	}

	public static String getDeclarationName(ASTNode node) {
		if (node instanceof MethodNode)
			return ((MethodNode) node).getName();
		if (node instanceof Variable)
			return ((Variable) node).getName();
		if (node instanceof FieldNode)
			return ((FieldNode) node).getName();
		if (node instanceof PropertyNode)
			return ((PropertyNode) node).getName();
		if (node instanceof Parameter)
			return ((Parameter) node).getName();
		if (node instanceof ClassNode)
			return ((ClassNode) node).getName();
		if (node instanceof ImportNode)
			return ((ImportNode) node).getClassName();
		return null;
	}

	private int findExactTokenOffset(String text, String name, int startOffset, int endOffset) {
		int found = startOffset;
		while (found >= 0) {
			found = text.indexOf(name, found);
			if (found == -1 || found >= endOffset) {
				return -1;
			}
			boolean beforeValid = (found == 0) || !Character.isJavaIdentifierPart(text.charAt(found - 1));
			boolean afterValid = (found + name.length() >= text.length())
					|| !Character.isJavaIdentifierPart(text.charAt(found + name.length()));
			if (beforeValid && afterValid) {
				return found;
			}
			found++;
		}
		return -1;
	}

	private SemanticTokens encodeDeltaTokens(List<Token> tokens) {
		List<Integer> data = new ArrayList<>();
		int prevLine = 0;
		int prevChar = 0;
		boolean first = true;
		for (Token t : tokens) {
			int deltaLine = first ? t.line : t.line - prevLine;
			int deltaStart = first ? t.startChar : (deltaLine == 0 ? t.startChar - prevChar : t.startChar);
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

	private int lineColToOffset(String text, int line, int col) {
		if (line < 0)
			return -1;
		int curLine = 0;
		int offset = 0;
		int len = text.length();
		while (offset < len && curLine < line) {
			if (text.charAt(offset) == '\n') {
				curLine++;
			}
			offset++;
		}
		if (curLine != line)
			return -1;
		return Math.min(offset + col, len);
	}

	private static class Token {
		final int line;
		final int startChar;
		final int length;
		final int type;
		final int modifiers;

		Token(int line, int startChar, int length, int tokenType, int modifiers) {
			this.line = line;
			this.startChar = startChar;
			this.length = length;
			this.type = tokenType;
			this.modifiers = modifiers;
		}
	}

	private Position toLineCol(String text, int offset) {
		int line = 0;
		int col = 0;
		int i = 0;
		while (i < offset) {
			char c = text.charAt(i);
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
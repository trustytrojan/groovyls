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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.compiler.util.GroovydocUtils;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;

public class CompletionProvider {
	private static final int MAX_ITEM_COUNT = 1000;

	private final ASTNodeVisitor ast;
	private final ScanResult classGraphScanResult;

	private boolean isIncomplete = false;

	public CompletionProvider(final ASTNodeVisitor ast, final ScanResult classGraphScanResult) {
		this.ast = ast;
		this.classGraphScanResult = classGraphScanResult;
	}

	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> provideCompletion(
			final TextDocumentIdentifier textDocument, final Position position, final CompletionContext context) {
		if (ast == null)
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));

		final var uri = URI.create(textDocument.getUri());
		final var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());

		if (offsetNode == null)
			return CompletableFuture.completedFuture(Either.forLeft(List.of()));

		final var parentNode = ast.getParent(offsetNode);
		final var isInNodeBlock = isInsideNodeBlock(offsetNode);

		isIncomplete = false;
		final var items = new ArrayList<CompletionItem>();

		if (offsetNode instanceof final PropertyExpression pe) {
			populateItemsFromPropertyExpression(pe, position, items);
		} else if (parentNode instanceof final PropertyExpression pe) {
			populateItemsFromPropertyExpression(pe, position, items);
		} else if (offsetNode instanceof final MethodCallExpression mce) {
			populateItemsFromMethodCallExpression(mce, position, items);
		} else if (offsetNode instanceof final ConstructorCallExpression cce) {
			populateItemsFromConstructorCallExpression(cce, position, items);
		} else if (parentNode instanceof final MethodCallExpression mce) {
			populateItemsFromMethodCallExpression(mce, position, items);
		} else if (offsetNode instanceof final VariableExpression ve) {
			populateItemsFromVariableExpression(ve, position, items, isInNodeBlock);
		} else if (offsetNode instanceof final ImportNode in) {
			populateItemsFromImportNode(in, position, items);
		} else if (offsetNode instanceof final ClassNode cn) {
			populateItemsFromClassNode(cn, position, items);
		} else if (offsetNode instanceof MethodNode) {
			populateItemsFromScope(offsetNode, "", items, isInNodeBlock);
		} else if (offsetNode instanceof Statement) {
			populateItemsFromScope(offsetNode, "", items, isInNodeBlock);
		}

		if (isIncomplete)
			return CompletableFuture.completedFuture(Either.forRight(new CompletionList(true, items)));

		return CompletableFuture.completedFuture(Either.forLeft(items));
	}

	private void populateItemsFromPropertyExpression(final PropertyExpression propExpr, final Position position,
			final List<CompletionItem> items) {
		if (!(GroovyLanguageServerUtils.astNodeToRange(propExpr.getProperty()) instanceof final Range propertyRange))
			return;
		final var memberName = getMemberName(propExpr.getPropertyAsString(), propertyRange, position);
		populateItemsFromExpression(propExpr.getObjectExpression(), memberName, items);
	}

	private void populateItemsFromMethodCallExpression(final MethodCallExpression methodCallExpr,
			final Position position, final List<CompletionItem> items) {
		if (!(GroovyLanguageServerUtils.astNodeToRange(methodCallExpr.getMethod()) instanceof final Range methodRange))
			return;
		final var memberName = getMemberName(methodCallExpr.getMethodAsString(), methodRange, position);
		populateItemsFromExpression(methodCallExpr.getObjectExpression(), memberName, items);
	}

	private void populateItemsFromImportNode(final ImportNode importNode, final Position position,
			final List<CompletionItem> items) {
		if (!(GroovyLanguageServerUtils.astNodeToRange(importNode) instanceof final Range importRange))
			return;
		// skip the "import " at the beginning
		importRange.setStart(new Position(importRange.getEnd().getLine(),
				importRange.getEnd().getCharacter() - importNode.getType().getName().length()));
		final var importText = getMemberName(importNode.getType().getName(), importRange, position);

		final var enclosingModule = (ModuleNode) GroovyASTUtils.getEnclosingNodeOfType(importNode, ModuleNode.class,
				ast);

		final var enclosingPackageName = enclosingModule != null ? enclosingModule.getPackageName() : null;
		final var importNames = enclosingModule != null ? enclosingModule.getImports().stream()
				.map(otherImportNode -> otherImportNode.getClassName()).collect(Collectors.toList())
				: Collections.emptyList();

		ast.getClassNodes().stream().filter(classNode -> {
			final var packageName = classNode.getPackageName();
			if (packageName == null || packageName.length() == 0 || packageName.equals(enclosingPackageName))
				return false;
			final var className = classNode.getName();
			final var classNameWithoutPackage = classNode.getNameWithoutPackage();
			if (!className.startsWith(importText) && !classNameWithoutPackage.startsWith(importText))
				return false;
			if (importNames.contains(className))
				return false;
			return true;
		}).map(classNode -> {
			final var item = new CompletionItem();
			item.setLabel(classNode.getName());
			item.setTextEdit(Either.forLeft(new TextEdit(importRange, classNode.getName())));
			item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(classNode));
			if (classNode.getNameWithoutPackage().startsWith(importText))
				item.setSortText(classNode.getNameWithoutPackage());
			final var markdownDocs = GroovydocUtils.groovydocToMarkdownDescription(classNode.getGroovydoc());
			if (markdownDocs != null)
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, markdownDocs));
			return item;
		}).forEach(items::add);

		if (classGraphScanResult == null)
			return;
		final var classes = classGraphScanResult.getAllClasses();
		final var packages = classGraphScanResult.getPackageInfo();

		packages.stream().filter(packageInfo -> {
			final var packageName = packageInfo.getName();
			if (packageName.startsWith(importText))
				return true;
			return false;
		}).map(packageInfo -> {
			final var item = new CompletionItem();
			item.setLabel(packageInfo.getName());
			item.setTextEdit(Either.forLeft(new TextEdit(importRange, packageInfo.getName())));
			item.setKind(CompletionItemKind.Module);
			return item;
		}).forEach(items::add);

		classes.stream().filter(classInfo -> {
			final var packageName = classInfo.getPackageName();
			if (packageName == null || packageName.length() == 0 || packageName.equals(enclosingPackageName))
				return false;
			final var className = classInfo.getName();
			final var classNameWithoutPackage = classInfo.getSimpleName();
			if (!className.startsWith(importText) && !classNameWithoutPackage.startsWith(importText))
				return false;
			if (importNames.contains(className))
				return false;
			return true;
		}).map(classInfo -> {
			final var item = new CompletionItem();
			item.setLabel(classInfo.getName());
			item.setTextEdit(Either.forLeft(new TextEdit(importRange, classInfo.getName())));
			item.setKind(classInfoToCompletionItemKind(classInfo));
			if (classInfo.getSimpleName().startsWith(importText))
				item.setSortText(classInfo.getSimpleName());
			return item;
		}).forEach(items::add);
	}

	private void populateItemsFromClassNode(final ClassNode classNode, final Position position,
			final List<CompletionItem> items) {
		final var parentNode = ast.getParent(classNode);

		if (!(parentNode instanceof final ClassNode parentClassNode))
			return;
		if (!(GroovyLanguageServerUtils.astNodeToRange(classNode) instanceof final Range classRange))
			return;

		final var className = getMemberName(classNode.getUnresolvedName(), classRange, position);

		if (classNode.equals(parentClassNode.getUnresolvedSuperClass()))
			populateTypes(classNode, className, new HashSet<>(), true, false, false, items);
		else if (Arrays.asList(parentClassNode.getUnresolvedInterfaces()).contains(classNode))
			populateTypes(classNode, className, new HashSet<>(), false, true, false, items);
	}

	private void populateItemsFromConstructorCallExpression(final ConstructorCallExpression constructorCallExpr,
			final Position position, final List<CompletionItem> items) {
		if (!(GroovyLanguageServerUtils.astNodeToRange(constructorCallExpr.getType()) instanceof final Range typeRange))
			return;
		final var typeName = getMemberName(constructorCallExpr.getType().getNameWithoutPackage(), typeRange, position);
		populateTypes(constructorCallExpr, typeName, new HashSet<>(), true, false, false, items);
	}

	private boolean isInsideNodeBlock(final ASTNode node) {
		var current = node;
		while (current != null) {
			if (current instanceof final MethodCallExpression call) {
				if ("node".equals(call.getMethodAsString())) {
					return true;
				}
			}
			current = ast.getParent(current);
		}
		return false;
	}

	private void populateItemsFromVariableExpression(final VariableExpression varExpr, final Position position,
			final List<CompletionItem> items, final boolean isInNodeBlock) {
		if (!(GroovyLanguageServerUtils.astNodeToRange(varExpr) instanceof final Range varRange))
			return;
		final var memberName = getMemberName(varExpr.getName(), varRange, position);
		populateItemsFromScope(varExpr, memberName, items, isInNodeBlock);
	}

	private void populateItemsFromPropertiesAndFields(final List<PropertyNode> properties, final List<FieldNode> fields,
			final String memberNamePrefix, final Set<String> existingNames, final List<CompletionItem> items) {
		properties.stream().filter(property -> {
			final var name = property.getName();
			// sometimes, a property and a field will have the same name
			if (name.startsWith(memberNamePrefix) && !existingNames.contains(name)) {
				existingNames.add(name);
				return true;
			}
			return false;
		}).map(property -> {
			final var item = new CompletionItem();
			item.setLabel(property.getName());
			item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(property));
			final var markdownDocs = GroovydocUtils.groovydocToMarkdownDescription(property.getGroovydoc());
			if (markdownDocs != null)
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, markdownDocs));
			final var labelDetails = new CompletionItemLabelDetails();
			labelDetails.setDescription(property.getType().getNameWithoutPackage());
			item.setLabelDetails(labelDetails);
			return item;
		}).forEach(items::add);

		fields.stream().filter(field -> {
			final var name = field.getName();
			// sometimes, a property and a field will have the same name
			if (name.startsWith(memberNamePrefix) && !existingNames.contains(name)) {
				existingNames.add(name);
				return true;
			}
			return false;
		}).map(field -> {
			final var item = new CompletionItem();
			item.setLabel(field.getName());
			item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(field));
			final var markdownDocs = GroovydocUtils.groovydocToMarkdownDescription(field.getGroovydoc());
			if (markdownDocs != null)
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, markdownDocs));
			final var labelDetails = new CompletionItemLabelDetails();
			labelDetails.setDescription(field.getType().getNameWithoutPackage());
			item.setLabelDetails(labelDetails);
			return item;
		}).forEach(items::add);
	}

	private void populateItemsFromMethods(final List<MethodNode> methods, final String memberNamePrefix,
			final Set<String> existingNames,
			final List<CompletionItem> items) {
		methods.stream().filter(method -> {
			final var methodName = method.getName();
			// overloads can cause duplicates
			if (methodName.startsWith(memberNamePrefix) && !existingNames.contains(methodName)) {
				existingNames.add(methodName);
				return true;
			}
			return false;
		}).map(method -> {
			final var item = new CompletionItem();
			item.setLabel(method.getName());
			var methodParams = "(";
			for (final var p : method.getParameters())
				methodParams += p.getType().getNameWithoutPackage() + ' ' + p.getName() + ", ";
			if (!methodParams.equals("("))
				methodParams = methodParams.substring(0, methodParams.length() - 2);
			methodParams += ')';
			final var labelDetails = new CompletionItemLabelDetails();
			labelDetails.setDetail(methodParams);
			var description = method.getReturnType().getNameWithoutPackage();
			if (method.getNodeMetaData("dgm") != null)
				description += " (DGM)";
			labelDetails.setDescription(description);
			item.setLabelDetails(labelDetails);
			item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(method));
			final var markdownDocs = GroovydocUtils.groovydocToMarkdownDescription(method.getGroovydoc());
			if (markdownDocs != null)
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, markdownDocs));
			return item;
		}).forEach(items::add);
	}

	private void populateItemsFromExpression(final Expression leftSide, final String memberNamePrefix,
			final List<CompletionItem> items) {
		final var existingNames = new HashSet<String>();

		final var properties = GroovyASTUtils.getPropertiesForLeftSideOfPropertyExpression(leftSide, ast);
		final var fields = GroovyASTUtils.getFieldsForLeftSideOfPropertyExpression(leftSide, ast);
		populateItemsFromPropertiesAndFields(properties, fields, memberNamePrefix, existingNames, items);

		final var methods = GroovyASTUtils.getMethodsForLeftSideOfPropertyExpression(leftSide, ast);
		populateItemsFromMethods(methods, memberNamePrefix, existingNames, items);
	}

	private void populateItemsFromVariableScope(final VariableScope variableScope, final String memberNamePrefix,
			final Set<String> existingNames, final List<CompletionItem> items) {
		variableScope.getDeclaredVariables().values().stream().filter(variable -> {
			final var variableName = variable.getName();
			// overloads can cause duplicates
			if (variableName.startsWith(memberNamePrefix) && !existingNames.contains(variableName)) {
				existingNames.add(variableName);
				return true;
			}
			return false;
		}).map(variable -> {
			final var item = new CompletionItem();
			item.setLabel(variable.getName());
			item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind((ASTNode) variable));
			final var labelDetails = new CompletionItemLabelDetails();
			labelDetails.setDescription(variable.getType().getNameWithoutPackage());
			item.setLabelDetails(labelDetails);
			if (variable instanceof final AnnotatedNode annotatedVar) {
				final var markdownDocs = GroovydocUtils.groovydocToMarkdownDescription(annotatedVar.getGroovydoc());
				if (markdownDocs != null)
					item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, markdownDocs));
			}
			return item;
		}).forEach(items::add);
	}

	private void populateItemsFromScope(final ASTNode node, final String namePrefix, final List<CompletionItem> items,
			final boolean isInNodeBlock) {
		final var existingNames = new HashSet<String>();
		var current = node;
		while (current != null) {
			if (current instanceof final ClassNode cn) {
				populateItemsFromPropertiesAndFields(cn.getProperties(), cn.getFields(), namePrefix,
						existingNames, items);
				populateItemsFromMethods(cn.getMethods(), namePrefix, existingNames, items);
			} else if (current instanceof final MethodNode mn) {
				populateItemsFromVariableScope(mn.getVariableScope(), namePrefix, existingNames, items);
			} else if (current instanceof final BlockStatement bs) {
				populateItemsFromVariableScope(bs.getVariableScope(), namePrefix, existingNames, items);
			}
			current = ast.getParent(current);
		}

		// GDSL symbols are now injected as methods into ClassNodes and will be
		// included through the normal method completion path above

		populateTypes(node, namePrefix, existingNames, items);
	}

	private void populateTypes(final ASTNode offsetNode, final String namePrefix, final Set<String> existingNames,
			final List<CompletionItem> items) {
		populateTypes(offsetNode, namePrefix, existingNames, true, true, true, items);
	}

	private void populateTypes(final ASTNode offsetNode, final String namePrefix, final Set<String> existingNames,
			final boolean includeClasses, final boolean includeInterfaces, final boolean includeEnums,
			final List<CompletionItem> items) {
		final var addImportRange = GroovyASTUtils.findAddImportRange(offsetNode, ast);

		final var enclosingModule = (ModuleNode) GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ModuleNode.class,
				ast);
		final var enclosingPackageName = enclosingModule != null ? enclosingModule.getPackageName() : null;
		final var importNames = enclosingModule != null
				? enclosingModule.getImports().stream().map(importNode -> importNode.getClassName())
						.collect(Collectors.toList())
				: Collections.emptyList();

		ast.getClassNodes().stream().filter(classNode -> {
			if (isIncomplete)
				return false;
			if (existingNames.size() >= MAX_ITEM_COUNT) {
				isIncomplete = true;
				return false;
			}
			final var classNameWithoutPackage = classNode.getNameWithoutPackage();
			final var className = classNode.getName();
			if (classNameWithoutPackage.startsWith(namePrefix) && !existingNames.contains(className)) {
				existingNames.add(className);
				return true;
			}
			return false;
		}).map(classNode -> {
			final var className = classNode.getName();
			final var packageName = classNode.getPackageName();
			final var item = new CompletionItem();
			item.setLabel(classNode.getNameWithoutPackage());
			item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(classNode));
			final var labelDetails = new CompletionItemLabelDetails();
			labelDetails.setDescription(packageName);
			item.setLabelDetails(labelDetails);
			final var markdownDocs = GroovydocUtils.groovydocToMarkdownDescription(classNode.getGroovydoc());
			if (markdownDocs != null)
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, markdownDocs));
			if (packageName != null && !packageName.equals(enclosingPackageName) && !importNames.contains(className)) {
				final var additionalTextEdits = new ArrayList<TextEdit>();
				final var addImportEdit = createAddImportTextEdit(className, addImportRange);
				additionalTextEdits.add(addImportEdit);
				item.setAdditionalTextEdits(additionalTextEdits);
			}
			return item;
		}).forEach(items::add);

		if (classGraphScanResult == null)
			return;

		classGraphScanResult.getAllClasses().stream().filter(classInfo -> {
			if (isIncomplete)
				return false;
			if (existingNames.size() >= MAX_ITEM_COUNT) {
				isIncomplete = true;
				return false;
			}
			final var className = classInfo.getName();
			final var classNameWithoutPackage = classInfo.getSimpleName();
			if (classNameWithoutPackage.startsWith(namePrefix) && !existingNames.contains(className)) {
				existingNames.add(className);
				return true;
			}
			return false;
		}).map(classInfo -> {
			final var className = classInfo.getName();
			final var packageName = classInfo.getPackageName();
			final var item = new CompletionItem();
			item.setLabel(classInfo.getSimpleName());
			item.setKind(classInfoToCompletionItemKind(classInfo));
			final var labelDetails = new CompletionItemLabelDetails();
			labelDetails.setDescription(packageName);
			item.setLabelDetails(labelDetails);
			if (packageName != null && !packageName.equals(enclosingPackageName) && !importNames.contains(className)) {
				final var additionalTextEdits = new ArrayList<TextEdit>();
				final var addImportEdit = createAddImportTextEdit(className, addImportRange);
				additionalTextEdits.add(addImportEdit);
				item.setAdditionalTextEdits(additionalTextEdits);
			}
			return item;
		}).forEach(items::add);
	}

	private String getMemberName(final String memberName, final Range range, final Position position) {
		if (position.getLine() == range.getStart().getLine()
				&& position.getCharacter() > range.getStart().getCharacter()) {
			final var length = position.getCharacter() - range.getStart().getCharacter();
			if (length > 0 && length <= memberName.length())
				return memberName.substring(0, length).trim();
		}
		return "";
	}

	private CompletionItemKind classInfoToCompletionItemKind(final ClassInfo classInfo) {
		if (classInfo.isInterface())
			return CompletionItemKind.Interface;
		if (classInfo.isEnum())
			return CompletionItemKind.Enum;
		return CompletionItemKind.Class;
	}

	private TextEdit createAddImportTextEdit(final String className, final Range range) {
		final var edit = new TextEdit();
		edit.setNewText("import %s\n".formatted(className));
		edit.setRange(range);
		return edit;
	}
}
package net.prominic.groovyls.providers;

import java.util.Objects;

import org.codehaus.groovy.ast.ASTNode;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.util.FileContentsTracker;

/**
 * Base class for LSP providers holding useful information and utility methods.
 */
class BaseProvider {
	protected final ASTNodeVisitor ast;
	protected final FileContentsTracker fct;

	protected BaseProvider(final ASTNodeVisitor ast, final FileContentsTracker fct) {
		this.ast = Objects.requireNonNull(ast);
		this.fct = Objects.requireNonNull(fct);
	}

	protected ASTNode getDefinition(final ASTNode node, final boolean strict) {
		return GroovyASTUtils.getDefinition(node, strict, ast, fct);
	}

	protected void debugPrint(final ASTNode node) {
		GroovyASTUtils.debugPrint(node, fct, ast);
	}
}

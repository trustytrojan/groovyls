package net.prominic.groovyls.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.prominic.groovyls.compiler.control.GroovyLSCompilationUnit;
import net.prominic.groovyls.util.FileContentsTracker;

class CompilationUnitFactoryTests {
	@Test
	void givesJenkinsfilesUniqueScriptClassNames(@TempDir Path workspaceRoot) throws Exception {
		Path firstJenkinsfile = workspaceRoot.resolve("service-a/Jenkinsfile");
		Path secondJenkinsfile = workspaceRoot.resolve("service-b/Jenkinsfile");
		Files.createDirectories(firstJenkinsfile.getParent());
		Files.createDirectories(secondJenkinsfile.getParent());
		Files.writeString(firstJenkinsfile, "pipeline { agent any }\n");
		Files.writeString(secondJenkinsfile, "pipeline { agent any }\n");

		GroovyLSCompilationUnit compilationUnit = new CompilationUnitFactory()
				.create(workspaceRoot, new FileContentsTracker());
		compilationUnit.compile(org.codehaus.groovy.control.Phases.CANONICALIZATION);

		Set<String> classNames = new HashSet<>();
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			for (ClassNode classNode : sourceUnit.getAST().getClasses()) {
				classNames.add(classNode.getName());
			}
		});

		assertEquals(2, classNames.size());
		assertFalse(compilationUnit.getErrorCollector().hasErrors());
	}
}
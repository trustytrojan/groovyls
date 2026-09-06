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
package net.prominic.groovyls.gdsl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import groovy.lang.groovydoc.Groovydoc;

/**
 * Manages GDSL symbol loading and caching for the language server.
 * Searches for gdsl.groovy files in the workspace and maintains a cache of
 * parsed symbols.
 */
public class GdslSymbolsManager {
    private List<JenkinsSymbol> cachedSymbols = Collections.emptyList();
    private long lastModified = 0;
    private File currentGdslFile = null;

    private final JenkinsGdslParser parser = new JenkinsGdslParser();

    /**
     * Loads GDSL symbols from the workspace.
     * Searches for gdsl.groovy in the workspace root and caches the results.
     *
     * @param workspaceRoot the root path of the workspace
     */
    public void loadGdslSymbols(Path workspaceRoot) {
        if (workspaceRoot == null) {
            cachedSymbols = Collections.emptyList();
            return;
        }

        // Search for gdsl.groovy files in the workspace root and one level deep
        List<File> gdslFiles = findGdslFiles(workspaceRoot.toFile());

        if (gdslFiles.isEmpty()) {
            cachedSymbols = Collections.emptyList();
            currentGdslFile = null;
            return;
        }

        // Use the first gdsl.groovy file found
        File gdslFile = gdslFiles.get(0);

        // Check if file has been modified
        long fileLastModified = gdslFile.lastModified();
        if (gdslFile.equals(currentGdslFile) && fileLastModified == lastModified && !cachedSymbols.isEmpty()) {
            // Cache is still valid
            return;
        }

        // Parse the GDSL file
        try {
            List<JenkinsSymbol> symbols = parser.parseGdsl(gdslFile);
            cachedSymbols = symbols;
            currentGdslFile = gdslFile;
            lastModified = fileLastModified;
            System.out.println("Loaded " + symbols.size() + " GDSL symbols from: " + gdslFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to load GDSL file: " + gdslFile.getAbsolutePath());
            e.printStackTrace();
            cachedSymbols = Collections.emptyList();
            currentGdslFile = null;
        }
    }

    /**
     * Gets the cached GDSL symbols.
     *
     * @return a list of cached JenkinsSymbol objects
     */
    public List<JenkinsSymbol> getSymbols() {
        return cachedSymbols;
    }

    /**
     * Injects GDSL symbols as synthetic methods into ClassNodes from Groovy source
     * files.
     * This makes GDSL methods available through the normal AST query mechanisms
     * instead of requiring special handling in providers.
     * Only ClassNodes that represent Groovy source files (not compiled Java
     * classes)
     * will receive GDSL symbol injections.
     *
     * @param classNodes collection of ClassNodes to potentially inject symbols into
     */
    public void injectGdslSymbolsIntoClassNodes(Collection<ClassNode> classNodes, ClassLoader cl) {
        if (cachedSymbols.isEmpty() || classNodes.isEmpty()) {
            return;
        }

        for (ClassNode classNode : classNodes) {
            if (!classNode.isScript()) {
                continue;
            }

            for (JenkinsSymbol symbol : cachedSymbols) {
                if (symbol.isProperty)
                    injectSymbolAsVariable(classNode.getMethod("run", new Parameter[] {}), symbol, cl);
                else
                    injectSymbolAsMethod(classNode, symbol);
            }
        }
    }

    /**
     * Creates a synthetic MethodNode from a JenkinsSymbol and adds it to a
     * ClassNode.
     * Includes documentation from the symbol if available.
     */
    private void injectSymbolAsMethod(ClassNode classNode, JenkinsSymbol symbol) {
        // Check if method already exists to avoid duplicates
        if (classNode.getMethod(symbol.name, convertParameters(symbol)) != null) {
            return;
        }

        // Create synthetic method node
        Parameter[] params = convertParameters(symbol);
        MethodNode methodNode = new MethodNode(
                symbol.name,
                0,
                ClassHelper.make(symbol.type),
                params,
                new ClassNode[0],
                null);

        // Mark as synthetic so it doesn't appear in regular code completion
        methodNode.setSynthetic(true);

        // Add documentation if available from the GDSL symbol
        if (symbol.doc != null && !symbol.doc.isEmpty()) {
            // Create Groovydoc object and attach to the method node
            Groovydoc groovydoc = new Groovydoc("/** " + symbol.doc + " */", methodNode);
            // Store in node metadata for retrieval by language features (hover, etc.)
            methodNode.putNodeMetaData(AnnotatedNode.DOC_COMMENT, groovydoc);
        }

        // Add method to the class
        classNode.addMethod(methodNode);
    }

    // @formatter:off
    // This mapping was made by inspecting the source code of each of these org.jenkinsci.plugins.workflow.cps.GlobalVariable extensions' getValue() methods.
    private static final Map<String, String> dslWrapperToActualType = Map.of(
        // https://github.com/jenkinsci/docker-workflow-plugin/blob/c9dc97d74ce3fa7ea0d95f3e1fa5f07d685932ee/src/main/java/org/jenkinsci/plugins/docker/workflow/DockerDSL.java#L51
        "org.jenkinsci.plugins.docker.workflow.DockerDSL", "org.jenkinsci.plugins.docker.workflow.Docker",

        // https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/6e7193cec599b402ee79ee9afc5b8a417eb43967/pipeline-model-definition/src/main/java/org/jenkinsci/plugins/pipeline/modeldefinition/ModelStepLoader.java#L61
        "org.jenkinsci.plugins.pipeline.modeldefinition.ModelStepLoader", "org.jenkinsci.plugins.pipeline.modeldefinition.ModelInterpreter",

        // https://github.com/jenkinsci/workflow-cps-plugin/blob/30c8c00684a37764a1083dd40b496ccdccc90dd7/plugin/src/main/java/org/jenkinsci/plugins/workflow/cps/EnvActionImpl.java#L202
        "org.jenkinsci.plugins.workflow.cps.EnvActionImpl.Binder", "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",

        // https://github.com/jenkinsci/workflow-cps-plugin/blob/30c8c00684a37764a1083dd40b496ccdccc90dd7/plugin/src/main/java/org/jenkinsci/plugins/workflow/cps/ParamsVariable.java#L76
        "org.jenkinsci.plugins.workflow.cps.ParamsVariable", "java.util.Map",

        // https://github.com/jenkinsci/workflow-cps-plugin/blob/30c8c00684a37764a1083dd40b496ccdccc90dd7/plugin/src/main/java/org/jenkinsci/plugins/workflow/cps/RunWrapperBinder.java#L43
        "org.jenkinsci.plugins.workflow.cps.RunWrapperBinder", "org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper",

        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/ec5b9e1806eca83c9f6f7677c18f4bb2d2d1afdf/src/main/java/org/jenkinsci/plugins/workflow/multibranch/SCMVar.java#L67
        "org.jenkinsci.plugins.workflow.multibranch.SCMVar", "hudson.scm.SCM",

        // https://github.com/jenkinsci/artifactory-plugin/blob/73be393da5913343dd55e0ca5b09cab864930abb/src/main/java/org/jfrog/hudson/pipeline/scripted/dsl/ArtifactoryDSL.java#L29
        "org.jfrog.hudson.pipeline.scripted.dsl.ArtifactoryDSL", "org.jfrog.hudson.pipeline.scripted.dsl.ArtifactoryPipelineGlobal",

        // https://github.com/jenkinsci/artifactory-plugin/blob/73be393da5913343dd55e0ca5b09cab864930abb/src/main/java/org/jfrog/hudson/pipeline/scripted/dsl/JFrogDSL.java#L26
        "org.jfrog.hudson.pipeline.scripted.dsl.JFrogDSL", "org.jfrog.hudson.pipeline.scripted.dsl.JFrogPipelineGlobal"
    );
    // @formatter:on

    /**
     * Creates a synthetic local variable declaration for `pipeline` (or any
     * JenkinsSymbol)
     * and inserts it at the top of a MethodNode's BlockStatement body.
     */
    private void injectSymbolAsVariable(final MethodNode methodNode, final JenkinsSymbol symbol, final ClassLoader cl) {
        final var code = methodNode.getCode();
        if (!(code instanceof final BlockStatement bs)) {
            return; // nothing to inject into
        }

        // Avoid duplicate injection
        if (bs.getVariableScope() != null
                && bs.getVariableScope().getDeclaredVariable(symbol.name) != null) {
            return;
        }

        Class<?> clazz;
        try {
            clazz = cl.loadClass((dslWrapperToActualType.get(symbol.type) instanceof final String s) ? s : symbol.type);
        } catch (ClassNotFoundException e) {
            System.err.println("Could not find class: " + e.getMessage());
            return;
        }

        final var varExpr = new VariableExpression(symbol.name, ClassHelper.make(clazz));
        final var declExpr = new DeclarationExpression(
                varExpr,
                Token.newSymbol(Types.ASSIGN, -1, -1),
                ConstantExpression.NULL);

        final var declStatement = new ExpressionStatement(declExpr);

        // Optional: attach documentation the same way the FieldNode version did
        if (symbol.doc != null && !symbol.doc.isEmpty()) {
            declStatement.putNodeMetaData(AnnotatedNode.DOC_COMMENT,
                    new Groovydoc("/** " + symbol.doc + " */", declExpr));
        }

        // Prepend so it's declared before any statement that references it
        bs.getStatements().add(0, declStatement);
    }

    /**
     * Converts JenkinsSymbol parameters to Groovy Parameter array.
     */
    private Parameter[] convertParameters(JenkinsSymbol symbol) {
        List<Parameter> params = new ArrayList<>();

        if (symbol.namedParams != null && !symbol.namedParams.isEmpty()) {
            for (Map<String, String> paramMap : symbol.namedParams) {
                String paramName = paramMap.get("name");
                ClassNode paramClassNode = ClassHelper.make(paramMap.get("type"));
                params.add(new Parameter(paramClassNode, paramName));
            }
        } else if (symbol.params != null && !symbol.params.isEmpty()) {
            for (Map.Entry<String, Object> entry : symbol.params.entrySet()) {
                String paramName = entry.getKey();
                Object paramType = entry.getValue();
                ClassNode paramClassNode;
                if (paramType instanceof String) {
                    String className = (String) paramType;
                    // Unfortunately need to hardcode here since Jenkins-generated GDSL files
                    // sometimes have unqualified types inside strings
                    if (className.equals("Closure"))
                        className = "groovy.lang.Closure";
                    if (className.equals("Map"))
                        className = "java.util.Map";
                    paramClassNode = ClassHelper.make(className);
                } else if (paramType instanceof Class<?>) {
                    paramClassNode = ClassHelper.make((Class<?>) paramType);
                } else {
                    paramClassNode = ClassHelper.OBJECT_TYPE;
                }
                params.add(new Parameter(paramClassNode, paramName));
            }
        }

        return params.toArray(new Parameter[0]);
    }

    /**
     * Searches for GDSL files in the workspace.
     * Looks for files named "gdsl.groovy" or ending in ".gdsl"
     */
    private List<File> findGdslFiles(File root) {
        List<File> result = new ArrayList<>();

        if (!root.exists() || !root.isDirectory()) {
            return result;
        }

        // Search root directory
        File[] files = root.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && isGdslFile(file)) {
                    result.add(file);
                }
            }
        }

        return result;
    }

    /**
     * Checks if a file is a GDSL file.
     */
    private boolean isGdslFile(File file) {
        String name = file.getName();
        return "gdsl.groovy".equalsIgnoreCase(name) ||
                name.endsWith(".gdsl") ||
                name.endsWith(".gdsl.groovy");
    }

    /**
     * Clears the cached symbols.
     */
    public void clear() {
        cachedSymbols = Collections.emptyList();
        currentGdslFile = null;
        lastModified = 0;
    }
}

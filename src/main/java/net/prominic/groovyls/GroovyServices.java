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
package net.prominic.groovyls;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraphException;
import io.github.classgraph.ScanResult;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.ast.MySTCVisitor;
import net.prominic.groovyls.compiler.control.GroovyLSCompilationUnit;
import net.prominic.groovyls.compiler.control.io.StringReaderSourceWithURI;
import net.prominic.groovyls.config.CompilationUnitFactory;
import net.prominic.groovyls.gdsl.GdslSymbolsManager;
import net.prominic.groovyls.providers.CompletionProvider;
import net.prominic.groovyls.providers.DefinitionProvider;
import net.prominic.groovyls.providers.DocumentSymbolProvider;
import net.prominic.groovyls.providers.HoverProvider;
import net.prominic.groovyls.providers.ReferenceProvider;
import net.prominic.groovyls.providers.RenameProvider;
import net.prominic.groovyls.providers.SemanticTokensProvider;
import net.prominic.groovyls.providers.SignatureHelpProvider;
import net.prominic.groovyls.providers.TypeDefinitionProvider;
import net.prominic.groovyls.providers.WorkspaceSymbolProvider;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;
import net.prominic.lsp.utils.Positions;

public class GroovyServices implements TextDocumentService, WorkspaceService, LanguageClientAware {
	private static final Pattern PATTERN_CONSTRUCTOR_CALL = Pattern.compile(".*new \\w*$");

	private LanguageClient languageClient;

	private Path workspaceRoot;
	private CompilationUnitFactory compilationUnitFactory;
	private GroovyLSCompilationUnit compilationUnit;
	private ASTNodeVisitor astVisitor;
	private Map<URI, List<Diagnostic>> prevDiagnosticsByFile;
	private FileContentsTracker fileContentsTracker = new FileContentsTracker();
	private ScanResult classGraphScanResult = null;
	private GroovyClassLoader classLoader = null;
	private GdslSymbolsManager gdslSymbolsManager = new GdslSymbolsManager();
	private SemanticTokensProvider semanticTokensProvider = null;
	private final Set<String> dependencyClasspaths = new HashSet<>();

	public GroovyServices(final CompilationUnitFactory factory) {
		compilationUnitFactory = factory;
	}

	public void setWorkspaceRoot(final Path workspaceRoot) {
		this.workspaceRoot = workspaceRoot;
		gdslSymbolsManager.loadGdslSymbols(workspaceRoot);
		createOrUpdateCompilationUnit();
	}

	@Override
	public void connect(final LanguageClient client) {
		languageClient = client;
	}

	// --- NOTIFICATIONS

	@Override
	public void didOpen(final DidOpenTextDocumentParams params) {
		final var uri = URI.create(params.getTextDocument().getUri());
		final var textDocument = params.getTextDocument();
		final var newText = textDocument.getText();

		final var existingSourceUnit = findSourceUnit(uri);
		final var previousContents = fileContentsTracker.getLastContents(uri);

		fileContentsTracker.didOpen(params);

		// Short-circuit: Reference check or length check before full text comparison
		if (existingSourceUnit != null && newText.equals(previousContents)) {
			fileContentsTracker.clearChanged(uri);
			return;
		}

		final var movedSourceUnit = (existingSourceUnit == null)
				? findSourceUnitWithContents(newText)
				: null;

		if (movedSourceUnit != null) {
			((StringReaderSourceWithURI) movedSourceUnit.getSource()).setURI(uri);
			fileContentsTracker.clearChanged(uri);
		}

		compileAndVisitAST();
	}

	@Override
	public void didChange(final DidChangeTextDocumentParams params) {
		fileContentsTracker.didChange(params);
		compileAndVisitAST();
	}

	@Override
	public void didClose(final DidCloseTextDocumentParams params) {
		fileContentsTracker.didClose(params);
	}

	@Override
	public void didSave(final DidSaveTextDocumentParams params) {
		// nothing to handle on save at this time
	}

	@Override
	public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {
		final var isSameUnit = createOrUpdateCompilationUnit();
		final var urisWithChanges = params.getChanges().stream()
				.map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());
		compile();
		if (isSameUnit)
			visitAST(urisWithChanges);
		else
			visitAST();
	}

	@Override
	public void didChangeConfiguration(final DidChangeConfigurationParams params) {
		if (!(params.getSettings() instanceof final JsonObject settings))
			return;
		updateSettings(settings);
	}

	private void updateSettings(final JsonObject settings) {
		if (!(settings.get("groovy") instanceof final JsonObject groovy))
			return;

		var dependenciesInstalled = false;
		if (groovy.get("dependencies") instanceof final JsonObject dependencies) {
			installDependencies(dependencies);
			dependenciesInstalled = true;
		}

		if (groovy.get("classpath") instanceof final JsonArray classpath)
			updateClasspath(StreamSupport.stream(classpath.spliterator(), false)
					.map(JsonElement::getAsString)
					.toList());
		else if (dependenciesInstalled)
			updateClasspath(new ArrayList<>());
	}

	private void updateClasspath(final List<String> classpathList) {
		classpathList.addAll(dependencyClasspaths);

		if (classpathList.equals(compilationUnitFactory.getAdditionalClasspathList()))
			return;

		compilationUnitFactory.setAdditionalClasspathList(classpathList);
		createOrUpdateCompilationUnit();
		compile();
		visitAST();
	}

	// --- REQUESTS

	@Override
	public CompletableFuture<Hover> hover(final HoverParams params) {
		final var provider = new HoverProvider(astVisitor);
		return provider.provideHover(params.getTextDocument(), params.getPosition(), fileContentsTracker);
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(final CompletionParams params) {
		final var textDocument = params.getTextDocument();
		final var position = params.getPosition();
		final var uri = URI.create(textDocument.getUri());

		SourceUnit originalSourceUnit = null, speculativeSourceUnit = null;
		final var offsetNode = astVisitor.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			final var originalSource = fileContentsTracker.getContents(uri);
			final var offset = Positions.getOffset(originalSource, position);
			final var lineBeforeOffset = originalSource.substring(offset - position.getCharacter(), offset);
			final var matcher = PATTERN_CONSTRUCTOR_CALL.matcher(lineBeforeOffset);
			final var placeholder = matcher.matches() ? "a()" : "a";
			originalSourceUnit = findSourceUnit(uri);
			speculativeSourceUnit = installSpeculativeSource(uri, originalSource, position, placeholder,
					originalSourceUnit);
		}

		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result = null;
		try {
			final var provider = new CompletionProvider(astVisitor, classGraphScanResult);
			result = provider.provideCompletion(params.getTextDocument(), params.getPosition(), params.getContext());
		} finally {
			if (originalSourceUnit != null) {
				restoreSpeculativeSource(uri, originalSourceUnit, speculativeSourceUnit);
			}
		}

		return result;
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			final DefinitionParams params) {
		final var provider = new DefinitionProvider(astVisitor);
		return provider.provideDefinition(params.getTextDocument(), params.getPosition(), fileContentsTracker);
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(final SignatureHelpParams params) {
		final var textDocument = params.getTextDocument();
		final var position = params.getPosition();
		final var uri = URI.create(textDocument.getUri());

		SourceUnit originalSourceUnit = null, speculativeSourceUnit = null;
		final var offsetNode = astVisitor.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			final var originalSource = fileContentsTracker.getContents(uri);
			originalSourceUnit = findSourceUnit(uri);
			speculativeSourceUnit = installSpeculativeSource(uri, originalSource, position, ")",
					originalSourceUnit);
		}

		try {
			final var provider = new SignatureHelpProvider(astVisitor);
			return provider.provideSignatureHelp(params.getTextDocument(), params.getPosition());
		} finally {
			if (originalSourceUnit != null) {
				restoreSpeculativeSource(uri, originalSourceUnit, speculativeSourceUnit);
			}
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			final TypeDefinitionParams params) {
		final var provider = new TypeDefinitionProvider(astVisitor);
		return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(final ReferenceParams params) {
		final var provider = new ReferenceProvider(astVisitor);
		return provider.provideReferences(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
			final DocumentSymbolParams params) {
		final var provider = new DocumentSymbolProvider(astVisitor);
		return provider.provideDocumentSymbols(params.getTextDocument());
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
		final var textDocument = params.getTextDocument();
		// Ensure semantic tokens provider is initialized
		if (semanticTokensProvider == null) {
			semanticTokensProvider = new SemanticTokensProvider(fileContentsTracker, astVisitor);
		}

		// Provide semantic tokens - GDSL symbols are injected before LSP transmission
		return CompletableFuture.completedFuture(semanticTokensProvider.provideFull(textDocument));
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			final WorkspaceSymbolParams params) {
		final var provider = new WorkspaceSymbolProvider(astVisitor);
		return provider.provideWorkspaceSymbols(params.getQuery());
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(final RenameParams params) {
		final var provider = new RenameProvider(astVisitor, fileContentsTracker);
		return provider.provideRename(params);
	}

	// --- INTERNAL

	private SourceUnit findSourceUnit(final URI uri) {
		if (compilationUnit == null) {
			return null;
		}
		final var result = new SourceUnit[1];
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			if (uri.equals(sourceUnit.getSource().getURI())) {
				result[0] = sourceUnit;
			}
		});
		return result[0];
	}

	private SourceUnit findSourceUnitWithContents(final String contents) {
		if (compilationUnit == null) {
			return null;
		}
		final var result = new SourceUnit[1];
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			final var sourceURI = sourceUnit.getSource().getURI();
			if (result[0] == null && contents.equals(fileContentsTracker.getLastContents(sourceURI))) {
				result[0] = sourceUnit;
			}
		});
		return result[0];
	}

	private SourceUnit installSpeculativeSource(final URI uri, final String originalSource, final Position position,
			final String placeholder, final SourceUnit originalSourceUnit) {
		if (originalSource == null || originalSourceUnit == null || compilationUnit == null) {
			return null;
		}
		final var offset = Positions.getOffset(originalSource, position);
		final var speculativeSource = originalSource.substring(0, offset) + placeholder
				+ originalSource.substring(offset);
		final var speculativeSourceUnit = new SourceUnit(Paths.get(uri).toString(),
				new StringReaderSourceWithURI(speculativeSource, uri, compilationUnit.getConfiguration()),
				compilationUnit.getConfiguration(), compilationUnit.getClassLoader(),
				compilationUnit.getErrorCollector());
		compilationUnit.removeSource(originalSourceUnit);
		compilationUnit.addSource(speculativeSourceUnit);
		compileSpeculative();
		visitAST(Collections.singleton(uri));
		return speculativeSourceUnit;
	}

	private void restoreSpeculativeSource(final URI uri, final SourceUnit originalSourceUnit,
			final SourceUnit speculativeSourceUnit) {
		if (compilationUnit == null || speculativeSourceUnit == null) {
			return;
		}
		compilationUnit.removeSource(speculativeSourceUnit);
		compilationUnit.restoreSource(originalSourceUnit);
		visitAST(Collections.singleton(uri));
	}

	private void compileSpeculative() {
		try {
			compilationUnit.compile(Phases.CANONICALIZATION);
		} catch (final CompilationFailedException e) {
			// The placeholder is only a code-intelligence aid; syntax errors are expected.
		} catch (final GroovyBugError e) {
			System.err.println("Unexpected exception in speculative Groovy compilation.");
			e.printStackTrace(System.err);
		} catch (final Exception e) {
			System.err.println("Unexpected exception in speculative Groovy compilation.");
			e.printStackTrace(System.err);
		}
	}

	/**
	 * Resolves a Maven package using the user's home ~/.m2 repository and returns a
	 * list of absolute JAR paths.
	 */
	public static List<String> downloadToDefaultM2(final String coords, final String remoteRepoUrl) throws Exception {
		// 1. Initialize engines
		final var locator = MavenRepositorySystemUtils.newServiceLocator();
		locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
		final var system = locator.getService(RepositorySystem.class);

		// 2. Point strictly to the global user home ~/.m2/repository
		final var session = MavenRepositorySystemUtils.newSession();
		final var m2Home = new File(System.getProperty("user.home"), ".m2/repository");
		final var localRepo = new LocalRepository(m2Home);
		session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

		// Fix org.jenkins-ci.plugins:artifactory depending on the JAR artifact of
		// org.codehaus.groovy:groovy-all by excluding it.
		// In your `groovy.dependencies` I recommend installing org.apache.groovy:groovy
		// instead
		final var groovyAllExclusion = new Exclusion("org.codehaus.groovy", "groovy-all", "*", "*");
		final var customExclusion = new ExclusionDependencySelector(Collections.singleton(groovyAllExclusion));

		// Combine your rule with standard Maven logic (optional deps handling, scope
		// filtering, etc.)
		session.setDependencySelector(new AndDependencySelector(
				MavenRepositorySystemUtils.newSession().getDependencySelector(),
				customExclusion));

		// 3. Define artifact details
		final var artifact = new DefaultArtifact(coords);
		final var dependency = new Dependency(artifact, "runtime");
		final var remoteRepo = new RemoteRepository.Builder("custom-repo", "default", remoteRepoUrl).build();

		// 4. Assemble requests
		final var collectRequest = new CollectRequest();
		collectRequest.setRoot(dependency);
		collectRequest.setRepositories(Collections.singletonList(remoteRepo));

		final var dependencyRequest = new DependencyRequest();
		dependencyRequest.setCollectRequest(collectRequest);

		final var result = system.resolveDependencies(session, dependencyRequest);

		// 5. Gather and return absolute paths for found runtime JAR files safely
		return result.getArtifactResults().stream()
				.map(a -> a.getArtifact().getFile().getAbsolutePath())
				.filter(path -> path.endsWith(".jar"))
				.collect(Collectors.toList());
	}

	// This is only called once on LS startup (only time when astVisitor is null).
	private void visitAST() {
		if (compilationUnit == null) {
			return;
		}
		astVisitor = new ASTNodeVisitor();
		astVisitor.visitCompilationUnit(compilationUnit);

		// Inject GDSL symbols as methods into ClassNodes so they're available
		// through normal AST queries in providers
		gdslSymbolsManager.injectGdslSymbolsIntoClassNodes(astVisitor.getClassNodes(),
				compilationUnit.getClassLoader());

		// Rerun variable scope visitor because GDSL "property" symbols are injected as
		// top-level variables now
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			final var moduleNode = sourceUnit.getAST();
			if (moduleNode == null)
				return;
			moduleNode.getClasses().forEach(c -> new VariableScopeVisitor(sourceUnit).visitClass(c));
		});

		runStaticTypeChecking();
	}

	// This is run on EVERY CHANGE to EVERY GROOVY FILE in the workspace.
	private void visitAST(final Set<URI> uris) {
		if (astVisitor == null) {
			visitAST();
			return;
		}
		if (compilationUnit == null) {
			return;
		}
		astVisitor.visitCompilationUnit(compilationUnit, uris);

		// Inject GDSL symbols as methods into ClassNodes so they're available
		// through normal AST queries in providers
		gdslSymbolsManager.injectGdslSymbolsIntoClassNodes(astVisitor.getClassNodes(),
				compilationUnit.getClassLoader());

		// Rerun variable scope visitor because GDSL "property" symbols are injected as
		// top-level variables now
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			final var moduleNode = sourceUnit.getAST();
			if (moduleNode == null)
				return;
			moduleNode.getClasses().forEach(c -> new VariableScopeVisitor(sourceUnit).visitClass(c));
		});

		runStaticTypeChecking();
	}

	private void installDependencies(final JsonObject dependencies) {
		for (final var entry : dependencies.entrySet()) {
			final var repositoryUrl = entry.getKey();
			if (!entry.getValue().isJsonArray())
				continue;
			System.err.println("Installing Maven dependencies from " + repositoryUrl);
			for (final var dep : entry.getValue().getAsJsonArray()) {
				if (!dep.isJsonPrimitive())
					continue;
				final var depString = dep.getAsString();
				try {
					System.err.println("Resolving dependency " + depString);
					dependencyClasspaths.addAll(downloadToDefaultM2(depString, repositoryUrl));
				} catch (final Exception e) {
					e.printStackTrace();
				}
			}
		}
		System.err.println("Finished installing all Maven dependencies");
	}

	private boolean createOrUpdateCompilationUnit() {
		if (compilationUnit != null) {
			final var targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && targetDirectory.exists()) {
				try {
					Files.walk(targetDirectory.toPath())
							.sorted(Comparator.reverseOrder())
							.map(Path::toFile)
							.forEach(File::delete);
				} catch (final IOException e) {
					System.err.println("Failed to delete target directory: " + targetDirectory.getAbsolutePath());
					compilationUnit = null;
					return false;
				}
			}
		}

		final var oldCompilationUnit = compilationUnit;
		compilationUnit = compilationUnitFactory.create(workspaceRoot, fileContentsTracker);
		fileContentsTracker.resetChangedFiles();

		if (compilationUnit != null) {
			final var targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
				System.err.println("Failed to create target directory: " + targetDirectory.getAbsolutePath());
			}
			final var newClassLoader = compilationUnit.getClassLoader();
			if (!newClassLoader.equals(classLoader)) {
				classLoader = newClassLoader;

				try {
					classGraphScanResult = new ClassGraph().overrideClassLoaders(classLoader).enableClassInfo()
							.enableSystemJarsAndModules()
							.scan();
				} catch (final ClassGraphException e) {
					classGraphScanResult = null;
				}
			}
		} else {
			classGraphScanResult = null;
		}

		return compilationUnit != null && compilationUnit.equals(oldCompilationUnit);
	}

	private void compileAndVisitAST() {
		final var isSameUnit = createOrUpdateCompilationUnit();
		if (isSameUnit) {
			compileSpeculative();
			visitAST();
		} else {
			compile();
			visitAST();
		}
	}

	private void compile() {
		if (compilationUnit == null) {
			return;
		}
		try {
			// AST is completely built after the canonicalization phase
			// for code intelligence, we shouldn't need to go further
			// http://groovy-lang.org/metaprogramming.html#_compilation_phases_guide
			compilationUnit.compile(Phases.CANONICALIZATION);
		} catch (final CompilationFailedException e) {
			// ignore
		} catch (final GroovyBugError e) {
			System.err.println("Unexpected exception in language server when compiling Groovy.");
			e.printStackTrace(System.err);
		} catch (final Exception e) {
			System.err.println("Unexpected exception in language server when compiling Groovy.");
			e.printStackTrace(System.err);
		}
		final var diagnostics = handleErrorCollector(compilationUnit.getErrorCollector());
		diagnostics.stream().forEach(languageClient::publishDiagnostics);
	}

	private void runStaticTypeChecking() {
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			if (sourceUnit == null || sourceUnit.getAST() == null) {
				return;
			}
			for (final var classNode : sourceUnit.getAST().getClasses()) {
				// We want STC to run on every change to the document.
				classNode.removeNodeMetaData(StaticTypeCheckingVisitor.class);
				classNode.getMethods().forEach(n -> n.removeNodeMetaData(StaticTypeCheckingVisitor.class));
				classNode.getDeclaredConstructors().forEach(n -> n.removeNodeMetaData(StaticTypeCheckingVisitor.class));

				final var visitor = new MySTCVisitor(sourceUnit, classNode);
				visitor.setCompilationUnit(compilationUnit);
				visitor.initialize();
				visitor.visitClass(classNode);
				visitor.performSecondPass();
			}
		});
	}

	private Set<PublishDiagnosticsParams> handleErrorCollector(final ErrorCollector collector) {
		final var diagnosticsByFile = new HashMap<URI, List<Diagnostic>>();

		final var errors = collector.getErrors();
		if (errors != null) {
			for (final var message : errors) {
				// TODO: also publish non-syntax-error diagnostics
				if (!(message instanceof final SyntaxErrorMessage syntaxErrorMessage))
					continue;
				final var cause = syntaxErrorMessage.getCause();
				var range = GroovyLanguageServerUtils.syntaxExceptionToRange(cause);
				if (range == null) {
					// range can't be null in a Diagnostic, so we need
					// a fallback
					range = new Range(new Position(0, 0), new Position(0, 0));
				}
				final var diagnostic = new Diagnostic();
				diagnostic.setRange(range);
				diagnostic.setSeverity(cause.isFatal() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning);
				diagnostic.setMessage(cause.getMessage());
				final var uri = Paths.get(cause.getSourceLocator()).toUri();
				diagnosticsByFile.computeIfAbsent(uri, (key) -> new ArrayList<>()).add(diagnostic);
			}
		}

		final var result = diagnosticsByFile.entrySet().stream()
				.map(entry -> new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()))
				.collect(Collectors.toSet());

		if (prevDiagnosticsByFile != null) {
			for (final var key : prevDiagnosticsByFile.keySet()) {
				if (!diagnosticsByFile.containsKey(key)) {
					// send an empty list of diagnostics for files that had
					// diagnostics previously or they won't be cleared
					result.add(new PublishDiagnosticsParams(key.toString(), new ArrayList<>()));
				}
			}
		}
		prevDiagnosticsByFile = diagnosticsByFile;
		return result;
	}
}
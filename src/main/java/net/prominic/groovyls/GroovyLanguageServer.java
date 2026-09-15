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

import java.net.URI;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import net.prominic.groovyls.config.CompilationUnitFactory;
import net.prominic.groovyls.config.ICompilationUnitFactory;
import net.prominic.groovyls.providers.SemanticTokensProvider;

public class GroovyLanguageServer implements LanguageServer, LanguageClientAware {
    @Override
    public void setTrace(final SetTraceParams params) {
        System.out.println("setTrace: " + params);
    }

    public static void main(final String[] args) {
        final var systemOut = System.out;
        // redirect System.out to System.err because we need to prevent
        // System.out from receiving anything that isn't an LSP message
        System.setOut(System.err);
        final var server = new GroovyLanguageServer();
        final var launcher = Launcher.createLauncher(server, LanguageClient.class, System.in, systemOut);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
    }

    private final GroovyServices groovyServices;

    public GroovyLanguageServer() {
        this(new CompilationUnitFactory());
    }

    public GroovyLanguageServer(final ICompilationUnitFactory compilationUnitFactory) {
        this.groovyServices = new GroovyServices(compilationUnitFactory);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(final InitializeParams params) {
        final var rootUriString = params.getRootUri();
        if (rootUriString != null) {
            final var uri = URI.create(params.getRootUri());
            final var workspaceRoot = Paths.get(uri);
            groovyServices.setWorkspaceRoot(workspaceRoot);
        }

        final var sc = new ServerCapabilities();

        sc.setCompletionProvider(new CompletionOptions(false, Arrays.asList(".")));
        sc.setTextDocumentSync(TextDocumentSyncKind.Full);
        sc.setDocumentSymbolProvider(true);
        sc.setWorkspaceSymbolProvider(true);
        sc.setDocumentSymbolProvider(true);
        sc.setReferencesProvider(true);
        sc.setDefinitionProvider(true);
        sc.setTypeDefinitionProvider(true);
        sc.setHoverProvider(true);
        sc.setRenameProvider(true);
        sc.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));

        final var stl = new SemanticTokensLegend(
                SemanticTokensProvider.SemanticTokenTypes.getList(),
                SemanticTokensProvider.SemanticTokenModifiers.getList());
        sc.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(stl, true));

        return CompletableFuture.completedFuture(new InitializeResult(sc));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return groovyServices;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return groovyServices;
    }

    @Override
    public void connect(final LanguageClient client) {
        groovyServices.connect(client);
    }
}

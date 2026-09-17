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
package net.prominic.groovyls.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;

import net.prominic.lsp.utils.Positions;

public class FileContentsTracker {
	private final Map<URI, String> openFiles = new HashMap<>();
	private final Set<URI> changedFiles = new HashSet<>();

	public Set<URI> getOpenURIs() {
		return openFiles.keySet();
	}

	public Set<URI> getChangedURIs() {
		return changedFiles;
	}

	public void resetChangedFiles() {
		changedFiles.clear();
	}

	public void forceChanged(final URI uri) {
		changedFiles.add(uri);
	}

	public boolean isOpen(final URI uri) {
		return openFiles.containsKey(uri);
	}

	public void didOpen(final DidOpenTextDocumentParams params) {
		final var uri = URI.create(params.getTextDocument().getUri());
		openFiles.put(uri, params.getTextDocument().getText());
		changedFiles.add(uri);
	}

	public void didChange(final DidChangeTextDocumentParams params) {
		final var uri = URI.create(params.getTextDocument().getUri());
		final var oldText = openFiles.get(uri);
		final var change = params.getContentChanges().get(0);
		final var range = change.getRange();
		if (range == null) {
			openFiles.put(uri, change.getText());
		} else {
			final var offsetStart = Positions.getOffset(oldText, change.getRange().getStart());
			final var offsetEnd = Positions.getOffset(oldText, change.getRange().getEnd());
			final var content = oldText.substring(0, offsetStart) + change.getText() + oldText.substring(offsetEnd);
			openFiles.put(uri, content);
		}
		changedFiles.add(uri);
	}

	public void didClose(final DidCloseTextDocumentParams params) {
		final var uri = URI.create(params.getTextDocument().getUri());
		openFiles.remove(uri);
	}

	public String getContents(final URI uri) {
		if (openFiles.containsKey(uri))
			return openFiles.get(uri);
		try {
			return Files.readString(Paths.get(uri));
		} catch (final IOException e) {
			return null;
		}
	}

	public void setContents(final URI uri, final String contents) {
		openFiles.put(uri, contents);
	}
}
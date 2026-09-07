## Fork notice

This is a fork of [GroovyLanguageServer/groovy-language-server](/GroovyLanguageServer/groovy-language-server) that adds:

- Jenkins pipeline step function support via parsing of GDSL files created by Jenkins instances.
- Semantic tokens over LSP for dynamic coloring/highlighting
- Many DX improvements within hovers, go-to-definition, completion menu, etc.

I mainly did this so that the VS Code experience is as close to using the [Red Hat Java extension](https://marketplace.visualstudio.com/items?itemName=redhat.java) as possible.

The original README continues below.

# Groovy Language Server

A [language server](https://microsoft.github.io/language-server-protocol/) for [Groovy](http://groovy-lang.org/). It is designed specifically for [Moonshine IDE](https://moonshine-ide.com), but it may work in other editors and environments.

The following language server protocol requests are currently supported:

- completion
- definition
- documentSymbol
- hover
- references
- rename
- signatureHelp
- symbol
- typeDefinition

The following configuration options are supported:

- groovy.java.home (`string` - sets a custom JDK path)
- groovy.classpath (`string[]` - sets a custom classpath to include _.jar_ files)

## Build

To build from the command line, run the following command:

```sh
./gradlew build
```

This will create _build/libs/groovyls-all.jar_.

## Run

To run the language server, use the following command:

```sh
java -jar groovyls-all.jar
```

Language server protocol messages are passed using standard I/O by default.

## Editors and IDEs

A sample language extension for Visual Studio Code is available in the _vscode-extension_ directory. There are no plans to release this extension to the VSCode Marketplace.

Instructions for setting up the language server in Sublime Text is available in the _sublime-text_ directory. Configuring the language server in other editors will likely be very similar.

Moonshine IDE natively provides a Grails project type that automatically configures the language server.

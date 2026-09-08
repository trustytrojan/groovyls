# groovyls

This is a fork of [GroovyLanguageServer/groovy-language-server](/GroovyLanguageServer/groovy-language-server) that adds:

- Jenkins pipeline step function support via parsing of GDSL files created by Jenkins instances.
- Semantic tokens over LSP for dynamic coloring/highlighting
- Many DX improvements within hovers, go-to-definition, completion menu, etc.
- Proper type inferencing for dynamically-typed variables and methods so that the LS services above are enriched

The goal is to make the developer experience as close to using [Eclipse JDT LS](https://github.com/eclipse-jdtls/eclipse.jdt.ls) as possible, which in turn improves the experience of Groovy development in VS Code.

## Build

To build from the command line, run the following command:

```sh
./gradlew build
```

This will create `build/libs/groovyls-all.jar`.

## Run

To run the language server, use the following command:

```sh
java -jar groovyls-all.jar
```

Language server protocol messages are passed using standard I/O by default.

## Editors and IDEs

A sample language extension for Visual Studio Code is available in the [vscode-extension](./vscode-extension) directory.

Instructions for setting up the language server in Sublime Text is available in the [sublime-text](./sublime-text) directory. Configuring the language server in other editors will likely be very similar.

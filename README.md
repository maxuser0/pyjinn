# Pyjinn

## Introduction

Pyjinn (pronounced like "pidgeon") is a scripting language with Python syntax that integrates deeply
with Java programs. It’s a "pidgin" language that looks and feels like Python while its
implementation and runtime behavior are based in Java. (The name "Pyjinn" is a portmanteau of
"Python" and "jinn", which is another word for "genie".)

While Pyjinn does not support the Python standard library, Pyjinn scripts can access the Java
standard library and any publicly accessible Java classes loaded into the Java program in which it's
embedded.

Pyjinn has minimal external dependencies. It depends on the ANTLR runtime library for parsing the
Python grammar, and Gson for JSON processing which is used for representing the Python AST.

## Getting Started

### Building and running the standalone interpreter

To build the standalone interpreter, run:

```
$ ./gradlew app:shadowJar
```

This builds the all-in-one jar file, `pyjinn-app-0.12-all.jar`:

```
% ls -lh app/build/libs/pyjinn-app-0.12-all.jar
-rw-r--r--@ 1 maxuser  staff   1.1M Aug 25 23:19 app/build/libs/pyjinn-app-0.12-all.jar
```

Run the standalone interpreter using `java -jar ...`, e.g. on Mac, Linux, or with WSL on Windows:

```
$ echo 'print([x for x in range(10) if x > 2])' |java -jar app/build/libs/pyjinn-app-0.12-all.jar
[3, 4, 5, 6, 7, 8, 9]
```

### Building the parser and interpreter for embedding in a Java app

To build the Pyjinn parser and interpreter for embedding in a Java application, run:

```
$ ./gradlew interpreter:shadowJar
```

This builds the jar file:

```
% ls -lh interpreter/build/libs/pyjinn-lib-0.12-all.jar
-rw-r--r--@ 1 maxuser  staff   926K Aug 25 23:10 interpreter/build/libs/pyjinn-lib-0.12-all.jar
```

## Code Structure

Code is organized into 4 subprojects: `grammar`, `parser`, `interpreter`, and `app`

### grammar

Uses ANTLR to generate Java parser APIs from `.g4` grammar files for parsing Python code.

### parser

Reads Python code using the generated `grammar` code to produce an abstract syntax tree (AST) in
JSON format that follows the AST structure of the Python `ast` module.

### interpreter

Reads Python source code (or AST in JSON format) and executes it. Builds a library suitable for
embedding in a Java app.

### app

Standalone app containing all required runtime dependencies for executing the parser and
interpreter on Python code passed on stdin.

## Java integration

Pyjinn scripts can access the Java runtime and the classes loaded into the enclosing Java program.
Python lists are convertible to Java lists, tuples to arrays, and str to String. Pyjinn can pass
Python lambdas and functions to Java method params that are an interface type that accepts Java
lambdas.  E.g.

```
$ java -jar app/build/libs/pyjinn-app-0.12-all.jar << 'EOF'
pythonList = [x for x in range(10) if x > 2]
javaList = pythonList.getJavaList()
filteredJavaList = javaList.stream().filter(lambda x: x < 7).map(lambda x: 2 * x).toList()
print(filteredJavaList)
EOF
[6, 8, 10, 12]  # <- output
```

## Publishing to local Maven repo

Make the Pyjinn library available to other projects by publishing it to your local Maven repository:

```
$ ./gradlew interpreter:publishToMavenLocal
```

On Mac, Linux, and WSL on Windows the Pyjinn library will be published to:

```
~/.m2/repository/org/pyjinn/pyjinn-lib/0.12/pyjinn-lib-0.12-all.jar
```

Make it available to other Gradle projects by adding the following to their `build.gradle`:

```
repositories {
    mavenLocal()
}

dependencies {
    implementation 'org.pyjinn:pyjinn-lib:0.12:all'
}
```
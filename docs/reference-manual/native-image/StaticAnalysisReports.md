---
layout: docs
toc_group: debugging-and-diagnostics
link_title: Points-to Analysis Reports
permalink: /reference-manual/native-image/debugging-and-diagnostics/StaticAnalysisReports/
redirect_from: /reference-manual/native-image/Reports/
---

# Points-to Analysis Reports

The points-to analysis produces two kinds of reports: an analysis call tree and an object tree. 
This information is produced by an intermediate step in the building process and represents the static analysis view of the call graph and heap object graph. 
These graphs are further transformed in the building process before they are compiled ahead-of-time into the binary and written into the binary heap, respectively.

In addition to the comprehensive report of the whole analysis universe, the points-to analysis can also produce reachability reports on why certain type/method/field is reachable.

## Call tree
The call tree is a a breadth-first tree reduction of the call graph as seen by the points-to analysis.
The points-to analysis eliminates calls to methods that it determines cannot be reachable at runtime, based on the analyzed receiver types.
It also completely eliminates invocations in unreachable code blocks, such as blocks guarded by a type check that always fails.
The call tree report is enabled using the `-H:+PrintAnalysisCallTree` command-line option and can be formatted either as a `TXT` file (default) or as a set of `CSV` files using the `-H:PrintAnalysisCallTreeType=CSV` option.

### TXT Format

When using the `TXT` format a file with the following structure is generated:

```
VM Entry Points
├── entry <entry-method> id=<entry-method-id>
│   ├── directly calls <callee> id=<callee-id> @bci=<invoke-bci>
│   │   └── <callee-sub-tree>
│   ├── virtually calls <callee> @bci=<invoke-bci>
│   │   ├── is overridden by <override-method-i> id=<override-method-i-id>
│   │   │   └── <callee-sub-tree>
│   │   └── is overridden by <override-method-j> id-ref=<override-method-j-id>
│   └── interfacially calls <callee> @bci=<invoke-bci>
│       ├── is implemented by <implementation-method-x> id=<implementation-method-x-id>
│       │   └── <callee-sub-tree>
│       └── is implemented by <implementation-method-y> id-ref=<implementation-method-y-id>
├── entry <entry-method> id=<entry-method-id>
│   └── <callee-sub-tree>
└── ...
```

The tags between `<`and `>` are expanded with concrete values, the remainder is printed as illustrated.
The methods are formatted using `<qualified-holder>.<method-name>(<qualified-parameters>):<qualified-return-type>` and are expanded until no more callees can be reached.

Since this is a tree reduction of the call graph each concrete method is expanded exactly once.
The tree representation inherently omits calls to methods that have already been explored in a different branch or previously on the same branch.
This restriction implicitly fixes the recursion problem.
To convey the information that is lost through tree reduction each concrete method is given a unique id.
Thus when a method is reached for the first time it declares an identifier as `id=<method-id>`.
Subsequent discoveries of the same method use an identifier reference to point to the previously expansion location: `id-ref=<method-id>`.
Each `id=<method-id>` and `id-ref=<method-id>` are followed by a blank space to make it easy to search.

Each invoke is tagged with the invocation bci: `@bci=<invoke-bci>`.
For invokes of inline methods the `<invoke-bci>` is a list of bci values, separated with `->`, enumerating the inline locations, backwards to the original invocation location.

### CSV Format
When using the `CSV` format a set of files containing raw data for methods and their relationships is generated.
The aim of these files is to enable this raw data to be easily imported into a graph database.
A graph database can provide the following functionality:

* Sophisticated graphical visualization of the call tree graph that provides a different perspective compared to text-based formats.
* Ability to execute complex queries that can (for example) show a subset of the tree that causes certain code path to be included in the call tree analysis.
  This querying functionality is crucial in making big analysis call trees manageable.

The process to import the files into a graph database is specific to each database.
Please follow the instructions provided by the graph database provider.

##  Object tree
The object tree is an exhaustive expansion of the objects included in the native binary heap.
The tree is obtained by a depth first walk of the native binary heap object graph.
It is enabled using the `-H:+PrintImageObjectTree` option.
The roots are either static fields or method graphs that contain embedded constants.
The printed values are concrete constant objects added to the native binary heap.
Produces a file with the structure:

```
Heap roots
├── root <root-field> value:
│   └── <value-type> id=<value-id> toString=<value-as-string> fields:
│       ├── <field-1> value=null
│       ├── <field-2> toString=<field-2-value-as-string> (expansion suppressed)
│       ├── <field-3> value:
│       │   └── <field-3-value-type> id=<field-3-value-id> toString=<field-3-value-as-string> fields:
│       │       └── <object-tree-rooted-at-field-3>
│       ├── <array-field-4> value:
│       │   └── <array-field-4-value-type> id=<array-field-4-value-id> toString=<array-field-4-value-as-string> elements (excluding null):
│       │       ├── [<index-i>] <element-index-i-value-type> id=<element-index-i-value-id> toString=<element-index-i-value-as-string> fields:
│       │       │   └── <object-tree-rooted-at-index-i>
│       │       └── [<index-j>] <element-index-j-value-type> id=<element-index-j-value-id> toString=<element-index-j-value-as-string> elements (excluding null):
│       │           └── <object-tree-rooted-at-index-j>
│       ├── <field-5> value:
│       │   └── <field-5-value-type> id-ref=<field-5-value-id> toString=<field-5-value-as-string>
│       ├── <field-6> value:
│       │   └── <field-6-value-type> id=<field-6-value-id> toString=<field-6-value-as-string> (no fields)
│       └── <array-field-7> value:
│           └── <array-field-7-value-type> id=<array-field-7-id> toString=<array-field-7-as-string> (no elements)
├── root <root-field> id-ref=<value-id> toString=<value-as-string>
├── root <root-method> value:
│   └── <object-tree-rooted-at-constant-embeded-in-the-method-graph>
└── ...
```

The tags between `<`and `>` are expanded with concrete values, the remainder is printed as illustrated.
The root fields are formatted using `<qualified-holder>.<field-name>:<qualified-declared-type>`.
The non-root fields are formatted using `<field-name>:<qualified-declared-type>`.
The value types are formatted using `<qualified-type>`.
The root methods are formatted using `<qualified-holder>.<method-name>(<unqualified-parameters>):<qualified-return-type>`
No-array objects are expanded for all fields (including null).
No-array objects with no fields are tagged with `(no fields)`.
Array objects are expanded for all non-null indexes: `[<element-index>] <object-tree-rooted-at-array-element>`
Empty array objects or with all null elements are tagged with `(no elements)`.

Each constant value is expanded exactly once to compress the format.
When a value is reached from multiple branches it is expanded only the first time and given an identifier: `id=<value-id>`.
Subsequent discoveries of the same value use an identifier reference to point to the previously expansion location: `id-ref=<value-id>`.

### Suppressing Expansion of Values

Some values, such as `String`, `BigInteger` and primitive arrays, are not expanded by default and marked with `(expansion suppressed)`.
All the other types are expanded by default.
To force the suppression of types expanded by default you can use `-H:ImageObjectTreeSuppressTypes=<comma-separated-patterns>`.
To force the expansion of types suppressed by default or through the option you can use `-H:ImageObjectTreeExpandTypes=<comma-separated-patterns>`.
When both `-H:ImageObjectTreeSuppressTypes` and `-H:ImageObjectTreeExpandTypes` are specified `-H:ImageObjectTreeExpandTypes` has precedence.

Similarly, some roots, such as `java.lang.Character$UnicodeBlock.map"` that prints a lot of strings, are not expanded at all and marked with `(expansion suppressed)` as well.
All the other roots are expanded by default.
To force the suppression of roots expanded by default you can use `-H:ImageObjectTreeSuppressRoots=<comma-separated-patterns>`.
To force the expansion of roots suppressed by default or through the option you can use `-H:ImageObjectTreeExpandRoots=<comma-separated-patterns>`.
When both `-H:ImageObjectTreeSuppressRoots` and `-H:ImageObjectTreeExpandRoots` are specified `-H:ImageObjectTreeExpandRoots` has precedence.

All the suppression/expansion options above accept a comma-separated list of patterns.
For types the pattern is based on the fully qualified name of the type and refers to the concrete type of the constants.
(For array types it is enough to specify the elemental type; it will match all the arrays of that type, of all dimensions.)
For roots the pattern is based on the string format of the root as described above.
The pattern accepts the `*` modifier:
  - ends-with: `*<str>` - the pattern exactly matches all entries that end with `<str>`
  - starts-with: `<str>*` - the pattern exactly matches all entries that start with `<str>`
  - contains: `*<str>*` - the pattern exactly matches all entries that contain `<str>`
  - equals: `<str>` - the pattern exactly matches all entries that are equal to `<str>`
  - all: `*` - the pattern matches all entries

#### Examples
Types suppression/expansion:
  - `-H:ImageObjectTreeSuppressTypes=java.io.BufferedWriter` - suppress the expansion of `java.io.BufferedWriter` objects
  - `-H:ImageObjectTreeSuppressTypes=java.io.BufferedWriter,java.io.BufferedOutputStream` - suppress the expansion of `java.io.BufferedWriter` and `java.io.BufferedOutputStream` objects
  - `-H:ImageObjectTreeSuppressTypes=java.io.*` - suppress the expansion of all `java.io.*` objects
  - `-H:ImageObjectTreeExpandTypes=java.lang.String` - force the expansion of `java.lang.String` objects
  - `-H:ImageObjectTreeExpandTypes=java.lang.String,java.math.BigInteger` - force the expansion of `java.lang.String` and `java.math.BigInteger` objects
  - `-H:ImageObjectTreeExpandTypes=java.lang.*` - force the expansion of all `java.lang.*` objects
  - `-H:ImageObjectTreeSuppressTypes=java.io.* -H:ImageObjectTreeExpandTypes=java.io.PrintStream` - suppress the expansion of all `java.io.*` but not `java.io.PrintStream` objects
  - `-H:ImageObjectTreeExpandTypes=*` - force the expansion of objects of all types, including those suppressed by default

Roots suppression/expansion:
  - `-H:ImageObjectTreeSuppressRoots="java.nio.charset.Charset.lookup(String)"` - suppress the expansion of all constants embedded in the graph of `com.oracle.svm.core.amd64.FrameAccess.wordSize()`
  - `-H:ImageObjectTreeSuppressRoots=java.util.*` - suppress the expansion of all roots that start with `java.util.`
  - `-H:ImageObjectTreeExpandRoots=java.lang.Character$UnicodeBlock.map` - force the expansion of `java.lang.Character$UnicodeBlock.map` static field root
  - `-H:ImageObjectTreeSuppressRoots=java.util.* -H:ImageObjectTreeExpandRoots=java.util.Locale` - suppress the expansion of all roots that start with `java.util.` but not `java.util.Locale`
  - `-H:ImageObjectTreeExpandRoots=*` - force the expansion of all roots, including those suppressed by default

## Reachability Report

In diagnosing code size or security problems, the developer often has the need to know why certain code element (type/method/field) is reachable.
Reachability reports are designed for the purpose.
There are three options for diagnosing the reachability reasons for types, methods, and fields respectively:

- `-H:AbortOnTypeReachable=<pattern>`
- `-H:AbortOnMethodReachable=<pattern>`
- `-H:AbortOnFieldReachable=<pattern>`

For each option, the right-hand side specifies the pattern of the code element to be diagnosed.

- The syntax for specifying types and fields is the same as that of suppression/expansion (See documentation for `-H:ImageObjectTreeSuppressTypes` above).
- The syntax for specifying methods is the same as that of method filters (See documentation for `-Djdk.graal.MethodFilter`).

When one of the option is enabled and the corresponding code element is reachable, a reachability trace will be dumped to a TXT file and Native Image will abort.
Here is an example of the reachability report for `-H:AbortOnTypeReachable=java.io.File`:

```
Type java.io.File is marked as allocated
at virtual method com.oracle.svm.core.jdk.NativeLibrarySupport.loadLibraryRelative(NativeLibrarySupport.java:105), implementation invoked
├── at virtual method com.oracle.svm.core.jdk.JNIPlatformNativeLibrarySupport.loadJavaLibrary(JNIPlatformNativeLibrarySupport.java:44), implementation invoked
│       ├── at virtual method com.oracle.svm.core.posix.PosixNativeLibrarySupport.loadJavaLibrary(PosixNativeLibraryFeature.java:117), implementation invoked
│       │       ├── at virtual method com.oracle.svm.core.posix.PosixNativeLibrarySupport.initializeBuiltinLibraries(PosixNativeLibraryFeature.java:98), implementation invoked
│       │       │       ├── at static method com.oracle.svm.core.graal.snippets.CEntryPointSnippets.initializeIsolate(CEntryPointSnippets.java:346), implementation invoked
│       │       │       │       str: static root method
│       │       │       └── type com.oracle.svm.core.posix.PosixNativeLibrarySupport is marked as in-heap
│       │       │               scanning root com.oracle.svm.core.posix.PosixNativeLibrarySupport@4839bf0d: com.oracle.svm.core.posix.PosixNativeLibrarySupport@4839bf0d embedded in
│       │       │                   org.graalvm.nativeimage.ImageSingletons.lookup(ImageSingletons.java)
│       │       │                   at static method org.graalvm.nativeimage.ImageSingletons.lookup(Class), intrinsified
│       │       │                       at static method com.oracle.svm.core.graal.snippets.CEntryPointSnippets.createIsolate(CEntryPointSnippets.java:209), implementation invoked
│       │       └── type com.oracle.svm.core.posix.PosixNativeLibrarySupport is marked as in-heap
│       └── type com.oracle.svm.core.jdk.JNIPlatformNativeLibrarySupport is reachable
└── type com.oracle.svm.core.jdk.NativeLibrarySupport is marked as in-heap
        scanning root com.oracle.svm.core.jdk.NativeLibrarySupport@6e06bbea: com.oracle.svm.core.jdk.NativeLibrarySupport@6e06bbea embedded in
            org.graalvm.nativeimage.ImageSingletons.lookup(ImageSingletons.java)
            at static method org.graalvm.nativeimage.ImageSingletons.lookup(Class), intrinsified
```

## Report Files

The reports are generated in the `reports` subdirectory, relative to the build directory.
When executing the `native-image` executable the build directory defaults to the working directory and can be modified using the `-H:Path=<dir>` option.

The call tree report name has the structure `call_tree_<binary_name>_<date_time>.txt` when using the `TXT` format or, when using the `CSV` format, the call tree reports' names have the structure `call_tree_*_<binary_name>_<date_time>.csv`.
When producing `CSV` formatted call tree reports, symbolic links following the structure `call_tree_*.csv` pointing to the latest call tree CSV reports are also generated.
The object tree report name has the structure: `object_tree_<binary_name>_<date_time>.txt`.
The binary name is the name of the generated binary, which can be set with the `-H:Name=<name>` option.
The `<date_time>` is in the `yyyyMMdd_HHmmss` format.

The reachability reports are also located in the reports directory.
They follow the same naming convention:

- Type reachability report: `trace_types_<binary_name>_<date_time>.txt`
- Method reachability report: `trace_methods_<binary_name>_<date_time>.txt`
- Field reachability report: `trace_fields_<binary_name>_<date_time>.txt`

## Further Reading

* [Hosted and Runtime Options](HostedvsRuntimeOptions.md)

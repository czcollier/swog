# scalanative-obj-interop
This project is a protoype for a Scala Native interop layer that allows idiomatic Scala to be used with external object systems. Read [Scala Native Interop for External Object Systems](https://github.com/jokade/scala-native/blob/topic/external-objects-design/docs/design/external_objects_interop.rst#syntactic-sugar) for more information about the object systems targeted, and the interop concepts explored in this project.

### sbt Settings
TBD

## General Notes
The extensions provided by this project use [macro annotations](http://docs.scala-lang.org/overviews/macros/annotations.html) to transform idiomatic Scala classes into the corresponding representation required for interop with the external object system.
You can inspect the generated code by adding the following `@debug` annotation to the annottee:
```scala
import scalanative.native._
import de.surfice.smacrotools.debug

@CObj
@debug
class Foo {
}
```

This annotation will print out the generated code to the console during compilation of `Foo`.

**Important**: due to a current limitation, you must `import de.surfice.smacrotools.debug` and then use the unqualified `@debug` annotation. Using the qualified annotation doesn't work.

## Interop for "informal" C objects
TBD
